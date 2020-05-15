package com.iexec.worker.docker;

import com.iexec.common.chain.ContributionAuthorization;
import com.iexec.common.dapp.DappType;
import com.iexec.common.sms.secret.TaskSecrets;
import com.iexec.common.task.TaskDescription;
import com.iexec.common.utils.FileHelper;
import com.iexec.worker.config.PublicConfigurationService;
import com.iexec.worker.config.WorkerConfigurationService;
import com.iexec.worker.dataset.DataService;
import com.iexec.worker.docker.postcompute.PostComputeResult;
import com.iexec.worker.docker.precompute.PreComputeResult;
import com.iexec.worker.result.ResultService;
import com.iexec.worker.sms.SmsService;
import com.iexec.worker.tee.scone.SconeTeeService;

import com.iexec.worker.utils.LoggingUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;


@Slf4j
@Service
public class ComputationService {

    // env variables that will be injected in the container of a task computation
    private static final String IEXEC_IN_ENV_PROPERTY = "IEXEC_IN";
    private static final String IEXEC_OUT_ENV_PROPERTY = "IEXEC_OUT";
    private static final String IEXEC_DATASET_FILENAME_ENV_PROPERTY = "IEXEC_DATASET_FILENAME";
    private static final String IEXEC_BOT_TASK_INDEX_ENV_PROPERTY = "IEXEC_BOT_TASK_INDEX";
    private static final String IEXEC_BOT_SIZE_ENV_PROPERTY = "IEXEC_BOT_SIZE";
    private static final String IEXEC_BOT_FIRST_INDEX_ENV_PROPERTY = "IEXEC_BOT_FIRST_INDEX";
    private static final String IEXEC_NB_INPUT_FILES_ENV_PROPERTY = "IEXEC_NB_INPUT_FILES";
    private static final String IEXEC_INPUT_FILES_ENV_PROPERTY_PREFIX = "IEXEC_INPUT_FILE_NAME_";
    private static final String IEXEC_INPUT_FILES_FOLDER_ENV_PROPERTY = "IEXEC_INPUT_FILES_FOLDER";

    private SmsService smsService;
    private DataService dataService;
    private CustomDockerClient customDockerClient;
    private SconeTeeService sconeTeeService;
    private ResultService resultService;
    private WorkerConfigurationService workerConfigService;
    private PublicConfigurationService publicConfigService;

    public ComputationService(SmsService smsService,
                              DataService dataService,
                              CustomDockerClient customDockerClient,
                              SconeTeeService sconeTeeService,
                              ResultService resultService,
                              WorkerConfigurationService workerConfigService,
                              PublicConfigurationService publicConfigService) {

        this.smsService = smsService;
        this.dataService = dataService;
        this.customDockerClient = customDockerClient;
        this.sconeTeeService = sconeTeeService;
        this.resultService = resultService;
        this.workerConfigService = workerConfigService;
        this.publicConfigService = publicConfigService;
    }

    public boolean isValidAppType(String chainTaskId, DappType type) {
        if (type.equals(DappType.DOCKER)) {
            return true;
        }

        String errorMessage = "Application is not of type Docker";
        log.error(errorMessage + " [chainTaskId:{}]", chainTaskId);
        return false;
    }

    public boolean downloadApp(String chainTaskId, TaskDescription taskDescription) {
        boolean isValidAppType = isValidAppType(chainTaskId, taskDescription.getAppType());
        if (!isValidAppType) {
            return false;
        }

        return customDockerClient.pullImage(chainTaskId, taskDescription.getAppUri());
    }

    public boolean isAppDownloaded(String imageUri) {
        return customDockerClient.isImagePulled(imageUri);
    }

    /*
     * TEE: download post-compute image && create secure session
     * non TEE: download secrets && decrypt dataset (TODO: this should be rewritten or removed)
     * 
     */
    public PreComputeResult runPreCompute(TaskDescription taskDescription, ContributionAuthorization contributionAuth) {
        if (taskDescription.isTeeTask()) {
            String secureSessionId = runTeePreCompute(taskDescription, contributionAuth);
            return !secureSessionId.isEmpty() ? PreComputeResult.success(secureSessionId) : PreComputeResult.failure();
        }

        boolean isSuccess = runNonTeePreCompute(taskDescription, contributionAuth);
        return isSuccess ? PreComputeResult.success() : PreComputeResult.failure();
    }

    private String runTeePreCompute(TaskDescription taskDescription, ContributionAuthorization contributionAuth) {
        String chainTaskId = taskDescription.getChainTaskId();
        if (!customDockerClient.pullImage(chainTaskId, taskDescription.getTeePostComputeImage())) {
            log.error("Cannot pull TEE post compute image [chainTaskId:{}, imageUri:{}]",
                    chainTaskId, taskDescription.getTeePostComputeImage());
            return "";
        }

        String secureSessionId = smsService.createTeeSession(contributionAuth);
        if (secureSessionId.isEmpty()) {
            log.error("Cannot compute TEE task without secure session [chainTaskId:{}]", chainTaskId);
        } else {
            log.info("Secure session created [chainTaskId:{}, secureSessionId:{}]", chainTaskId, secureSessionId);
        }
        return secureSessionId;
    }

    private boolean runNonTeePreCompute(TaskDescription taskDescription, ContributionAuthorization contributionAuth) {
        String chainTaskId = taskDescription.getChainTaskId();
        Optional<TaskSecrets> oTaskSecrets = smsService.fetchTaskSecrets(contributionAuth);
        if (!oTaskSecrets.isPresent()) {
            log.warn("No secrets fetched for this task, will continue [chainTaskId:{}]:", chainTaskId);
        } else {
            String datasetSecretFilePath = workerConfigService.getDatasetSecretFilePath(chainTaskId);
            String beneficiarySecretFilePath = workerConfigService.getBeneficiarySecretFilePath(chainTaskId);
            String enclaveSecretFilePath = workerConfigService.getEnclaveSecretFilePath(chainTaskId);
            smsService.saveSecrets(chainTaskId, oTaskSecrets.get(), datasetSecretFilePath,
                    beneficiarySecretFilePath, enclaveSecretFilePath);
        }
        boolean isDatasetDecryptionNeeded = dataService.isDatasetDecryptionNeeded(chainTaskId);
        boolean isDatasetDecrypted = false;
        if (isDatasetDecryptionNeeded) {
            isDatasetDecrypted = dataService.decryptDataset(chainTaskId, taskDescription.getDatasetUri());
        }

        if (isDatasetDecryptionNeeded && !isDatasetDecrypted) {
            log.error("Failed to decrypt dataset [chainTaskId:{}, uri:{}]",
                    chainTaskId, taskDescription.getDatasetUri());
            return false;
        }
        return true;
    }

    public String runComputation(TaskDescription taskDescription, PreComputeResult preComputeResult) {
        String chainTaskId = taskDescription.getChainTaskId();
        List<String> env = getContainerEnvVariables(taskDescription);

        if (taskDescription.isTeeTask()) {
            List<String> teeEnv = sconeTeeService.buildSconeDockerEnv(preComputeResult.getSecureSessionId() + "/app",
                    publicConfigService.getSconeCasURL(), "1G");
            env.addAll(teeEnv);
        }

        Map<String, String> bindPaths = new HashMap<>();
        bindPaths.put(workerConfigService.getTaskInputDir(chainTaskId), FileHelper.SLASH_IEXEC_IN);
        bindPaths.put(workerConfigService.getTaskIexecOutDir(chainTaskId), FileHelper.SLASH_IEXEC_OUT);

        DockerExecutionConfig appExecutionConfig = DockerExecutionConfig.builder()
                .chainTaskId(chainTaskId)
                .containerName(getTaskContainerName(chainTaskId))
                .imageUri(taskDescription.getAppUri())
                .cmd(taskDescription.getCmd())
                .maxExecutionTime(taskDescription.getMaxExecutionTime())
                .env(env)
                .bindPaths(bindPaths)
                .isSgx(taskDescription.isTeeTask())
                .build();

        DockerExecutionResult appExecutionResult = customDockerClient.execute(appExecutionConfig);
        if (shouldPrintDeveloperLogs(taskDescription)){
            log.info("Developer logs of computing stage [chainTaskId:{}, logs:{}]", chainTaskId,
                    getDockerExecutionDeveloperLogs(chainTaskId, appExecutionResult));
        }

        if (!appExecutionResult.isSuccess()) {
            log.error("Failed to compute [chainTaskId:{}]", chainTaskId);
            return false;
        }

        //TODO: Remove logs before merge
        System.out.println("****** App");
        System.out.println(appExecutionResult.getStdout());
        return appExecutionResult.getStdout();
    }


    /*
     * Move files produced by the compute stage in <SLASH_IEXEC_OUT>
     * to <SLASH_RESULT>. In TEE mode, those files will be
     * encrypted if requested.
     */
    public void runPostCompute(TaskDescription taskDescription) {
        if (taskDescription.isTeeTask()) {
            runTeePostCompute(taskDescription);
        } else {
            runNonTeePostCompute(taskDescription);
        }
    }

    private PostComputeResult runTeePostCompute(String secureSessionId, TaskDescription taskDescription) {
        String chainTaskId = taskDescription.getChainTaskId();
        List<String> sconeUploaderEnv = sconeTeeService.buildSconeDockerEnv(secureSessionId + "/post-compute",
                publicConfigService.getSconeCasURL(), "3G");

        Map<String, String> bindPaths = new HashMap<>();
        bindPaths.put(workerConfigService.getTaskIexecOutDir(chainTaskId), FileHelper.SLASH_IEXEC_OUT);
        bindPaths.put(workerConfigService.getTaskResultDir(chainTaskId), FileHelper.SLASH_RESULT);

        DockerExecutionConfig teePostComputeExecutionConfig = DockerExecutionConfig.builder()
                .chainTaskId(chainTaskId)
                .containerName(getTaskTeePostComputeContainerName(chainTaskId))
                .imageUri(taskDescription.getTeePostComputeImage())
                .maxExecutionTime(taskDescription.getMaxExecutionTime())
                .env(sconeUploaderEnv)
                .bindPaths(bindPaths)
                .isSgx(true)
                .build();

        DockerExecutionResult teePostComputeExecutionResult = customDockerClient.execute(teePostComputeExecutionConfig);
        if (!teePostComputeExecutionResult.isSuccess()) {
            log.error("Failed to process post-compute on result [chainTaskId:{}]", chainTaskId);
            return PostComputeResult.failure();
        }
        System.out.println("****** Tee post-compute");
        System.out.println(teePostComputeExecutionResult.getStdout());
        return PostComputeResult.success(teePostComputeExecutionResult.getStdout());
        // return resultService.saveResult(chainTaskId, taskDescription, stdout);
    }

    private boolean runNonTeePostCompute(TaskDescription taskDescription) {
        // mv files
    }














    // #############################################################
    // #############################################################
    // #############################################################
    // #############################################################


    // public boolean runNonTeeComputation(TaskDescription taskDescription,
    //                                     ContributionAuthorization contributionAuth) {
    //     String chainTaskId = taskDescription.getChainTaskId();
    //     String imageUri = taskDescription.getAppUri();
    //     String cmd = taskDescription.getCmd();
    //     long maxExecutionTime = taskDescription.getMaxExecutionTime();

    //     // fetch task secrets from SMS
    //     Optional<TaskSecrets> oTaskSecrets = smsService.fetchTaskSecrets(contributionAuth);
    //     if (!oTaskSecrets.isPresent()) {
    //         log.warn("No secrets fetched for this task, will continue [chainTaskId:{}]:", chainTaskId);
    //     } else {
    //         String datasetSecretFilePath = workerConfigService.getDatasetSecretFilePath(chainTaskId);
    //         String beneficiarySecretFilePath = workerConfigService.getBeneficiarySecretFilePath(chainTaskId);
    //         String enclaveSecretFilePath = workerConfigService.getEnclaveSecretFilePath(chainTaskId);
    //         smsService.saveSecrets(chainTaskId, oTaskSecrets.get(), datasetSecretFilePath,
    //                 beneficiarySecretFilePath, enclaveSecretFilePath);
    //     }

    //     // decrypt data
    //     boolean isDatasetDecryptionNeeded = dataService.isDatasetDecryptionNeeded(chainTaskId);
    //     boolean isDatasetDecrypted = false;

    //     if (isDatasetDecryptionNeeded) {
    //         isDatasetDecrypted = dataService.decryptDataset(chainTaskId, taskDescription.getDatasetUri());
    //     }

    //     if (isDatasetDecryptionNeeded && !isDatasetDecrypted) {
    //         log.error("Failed to decrypt dataset [chainTaskId:{}, uri:{}]",
    //                 chainTaskId, taskDescription.getDatasetUri());
    //         return false;
    //     }

    //     // compute
    //     String datasetFilename = FileHelper.getFilenameFromUri(taskDescription.getDatasetUri());
    //     List<String> env = getContainerEnvVariables(datasetFilename, taskDescription);

    //     DockerExecutionConfig dockerExecutionConfig = DockerExecutionConfig.builder()
    //             .chainTaskId(chainTaskId)
    //             .containerName(getTaskContainerName(chainTaskId))
    //             .imageUri(imageUri)
    //             .cmd(cmd)
    //             .maxExecutionTime(maxExecutionTime)
    //             .env(env)
    //             .bindPaths(getDefaultBindPaths(chainTaskId))
    //             .isSgx(false)
    //             .build();

    //     DockerExecutionResult appExecutionResult = customDockerClient.execute(dockerExecutionConfig);
    //     if (shouldPrintDeveloperLogs(taskDescription)){
    //         log.info("Developer logs of computing stage [chainTaskId:{}, logs:{}]", chainTaskId,
    //                 getDockerExecutionDeveloperLogs(chainTaskId, appExecutionResult));
    //     }

    //     if (!appExecutionResult.isSuccess()) {
    //         log.error("Failed to compute [chainTaskId:{}]", chainTaskId);
    //         return false;
    //     }

    //     return resultService.saveResult(chainTaskId, taskDescription, appExecutionResult.getStdout());
    // }

    // public boolean runTeeComputation(TaskDescription taskDescription,
    //                                  ContributionAuthorization contributionAuth) {
    //     String chainTaskId = contributionAuth.getChainTaskId();
    //     String imageUri = taskDescription.getAppUri();
    //     String datasetUri = taskDescription.getDatasetUri();
    //     String cmd = taskDescription.getCmd();
    //     long maxExecutionTime = taskDescription.getMaxExecutionTime();
    //     String teePostComputeImage = taskDescription.getTeePostComputeImage();

    //     if (!customDockerClient.pullImage(chainTaskId, teePostComputeImage)) {
    //         String msg = "runTeeComputation failed (pullImage teePostComputeImage)";
    //         log.error(msg + " [chainTaskId:{},teePostComputeImage:{}]", chainTaskId, teePostComputeImage);
    //         return false;
    //     }

    //     String secureSessionId = smsService.createTeeSession(contributionAuth);
    //     if (secureSessionId.isEmpty()) {
    //         log.error("Cannot compute TEE task without secure session [chainTaskId:{}]", chainTaskId);
    //         return false;
    //     }

    //     log.info("Secure session created [chainTaskId:{}, secureSessionId:{}]", chainTaskId, secureSessionId);
    //     ArrayList<String> sconeAppEnv = sconeTeeService.buildSconeDockerEnv(secureSessionId + "/app",
    //             publicConfigService.getSconeCasURL(), "1G");
    //     ArrayList<String> sconeUploaderEnv = sconeTeeService.buildSconeDockerEnv(secureSessionId + "/post-compute",
    //             publicConfigService.getSconeCasURL(), "3G");

    //     if (sconeAppEnv.isEmpty()) {
    //         log.error("Could not create scone docker environment [chainTaskId:{}]", chainTaskId);
    //         return false;
    //     }

    //     String datasetFilename = FileHelper.getFilenameFromUri(datasetUri);
    //     for (String envVar : getContainerEnvVariables(datasetFilename, taskDescription)) {
    //         sconeAppEnv.add(envVar);
    //     }

    //     DockerExecutionConfig appExecutionConfig = DockerExecutionConfig.builder()
    //             .chainTaskId(chainTaskId)
    //             .containerName(getTaskContainerName(chainTaskId))
    //             .imageUri(imageUri)
    //             .cmd(cmd)
    //             .maxExecutionTime(maxExecutionTime)
    //             .env(sconeAppEnv)
    //             .bindPaths(getTeeComputeBindPaths(chainTaskId))
    //             .isSgx(true)
    //             .build();

    //     // run computation
    //     DockerExecutionResult appExecutionResult = customDockerClient.execute(appExecutionConfig);

    //     if (shouldPrintDeveloperLogs(taskDescription)){
    //         log.info("Developer logs of computing stage [chainTaskId:{}, logs:{}]", chainTaskId,
    //                 getDockerExecutionDeveloperLogs(chainTaskId, appExecutionResult));
    //     }

    //     if (!appExecutionResult.isSuccess()) {
    //         log.error("Failed to compute [chainTaskId:{}]", chainTaskId);
    //         return false;
    //     }

    //     //TODO: Remove logs before merge
    //     System.out.println("****** App");
    //     System.out.println(appExecutionResult.getStdout());

    //     DockerExecutionConfig teePostComputeExecutionConfig = DockerExecutionConfig.builder()
    //             .chainTaskId(chainTaskId)
    //             .containerName(getTaskTeePostComputeContainerName(chainTaskId))
    //             .imageUri(teePostComputeImage)
    //             .maxExecutionTime(maxExecutionTime)
    //             .env(sconeUploaderEnv)
    //             .bindPaths(getTeePostComputeBindPaths(chainTaskId))
    //             .isSgx(true)
    //             .build();
    //     DockerExecutionResult teePostComputeExecutionResult = customDockerClient.execute(teePostComputeExecutionConfig);
    //     if (!teePostComputeExecutionResult.isSuccess()) {
    //         log.error("Failed to process post-compute on result [chainTaskId:{}]", chainTaskId);
    //         return false;
    //     }
    //     System.out.println("****** Tee post-compute");
    //     System.out.println(teePostComputeExecutionResult.getStdout());


    //     String stdout = appExecutionResult.getStdout()
    //             + teePostComputeExecutionResult.getStdout();


    //     return resultService.saveResult(chainTaskId, taskDescription, stdout);
    // }

    private List<String> getContainerEnvVariables(TaskDescription taskDescription) {
        String datasetFilename = FileHelper.getFilenameFromUri(taskDescription.getDatasetUri());
        List<String> list = new ArrayList<>();
        list.add(IEXEC_IN_ENV_PROPERTY + "=" + FileHelper.SLASH_IEXEC_IN);
        list.add(IEXEC_OUT_ENV_PROPERTY + "=" + FileHelper.SLASH_IEXEC_OUT);
        list.add(IEXEC_DATASET_FILENAME_ENV_PROPERTY + "=" + datasetFilename);
        list.add(IEXEC_BOT_SIZE_ENV_PROPERTY + "=" + taskDescription.getBotSize());
        list.add(IEXEC_BOT_FIRST_INDEX_ENV_PROPERTY + "=" + taskDescription.getBotFirstIndex());
        list.add(IEXEC_BOT_TASK_INDEX_ENV_PROPERTY + "=" + taskDescription.getBotIndex());
        int nbFiles = taskDescription.getInputFiles() == null ? 0 : taskDescription.getInputFiles().size();
        list.add(IEXEC_NB_INPUT_FILES_ENV_PROPERTY + "=" + nbFiles);

        int inputFileIndex = 1;
        for (String inputFile : taskDescription.getInputFiles()) {
            list.add(IEXEC_INPUT_FILES_ENV_PROPERTY_PREFIX + inputFileIndex + "=" + FilenameUtils.getName(inputFile));
            inputFileIndex++;
        }
        list.add(IEXEC_INPUT_FILES_FOLDER_ENV_PROPERTY + "=" + FileHelper.SLASH_IEXEC_IN);
        return list;
    }

    // private Map<String, String> getDefaultBindPaths(String chainTaskId) {
    //     Map<String, String> bindPaths = new HashMap<>();
    //     bindPaths.put(workerConfigService.getTaskInputDir(chainTaskId), FileHelper.SLASH_IEXEC_IN);
    //     bindPaths.put(workerConfigService.getTaskIexecOutDir(chainTaskId), FileHelper.SLASH_IEXEC_OUT);
    //     return bindPaths;
    // }

    // private Map<String, String> getTeeComputeBindPaths(String chainTaskId) {
    //     Map<String, String> bindPaths = getDefaultBindPaths(chainTaskId);
    //     bindPaths.put(workerConfigService.getTaskIexecOutUnprotectedDir(chainTaskId), FileHelper.SLASH_IEXEC_OUT_UNPROTECTED);
    //     return bindPaths;
    // }

    // private Map<String, String> getTeePostComputeBindPaths(String chainTaskId) {
    //     Map<String, String> bindPaths = new HashMap<>();
    //     bindPaths.put(workerConfigService.getTaskIexecOutDir(chainTaskId), FileHelper.SLASH_IEXEC_OUT);
    //     bindPaths.put(workerConfigService.getTaskIexecOutUnprotectedDir(chainTaskId), FileHelper.SLASH_IEXEC_OUT_UNPROTECTED);
    //     return bindPaths;
    // }

    // We use the name "worker1-0xabc123" for app container to avoid
    // conflicts when running multiple workers on the same machine.
    // Exp: integration tests
    private String getTaskContainerName(String chainTaskId) {
        return workerConfigService.getWorkerName() + "-" + chainTaskId;
    }

    private String getTaskTeePostComputeContainerName(String chainTaskId) {
        return getTaskContainerName(chainTaskId) + "-tee-post-compute";
    }

    private boolean shouldPrintDeveloperLogs(TaskDescription taskDescription) {
        return workerConfigService.isDeveloperLoggerEnabled() && taskDescription.isDeveloperLoggerEnabled();
    }

    private String getDockerExecutionDeveloperLogs(String chainTaskId, DockerExecutionResult dockerExecutionResult) {
        String iexecInTree = FileHelper.printDirectoryTree(new File(workerConfigService.getTaskInputDir(chainTaskId)));
        iexecInTree = iexecInTree.replace("├── input/", "├── iexec_in/");//confusing for developers if not replaced
        String iexecOutTree = FileHelper.printDirectoryTree(new File(workerConfigService.getTaskIexecOutDir(chainTaskId)));
        String stdout = dockerExecutionResult.getStdout();

        return LoggingUtils.prettifyDeveloperLogs(iexecInTree, iexecOutTree, stdout);
    }
}