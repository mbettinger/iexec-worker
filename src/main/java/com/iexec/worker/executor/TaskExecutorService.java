package com.iexec.worker.executor;

import com.iexec.common.chain.ChainReceipt;
import com.iexec.common.chain.ContributionAuthorization;
import com.iexec.common.dapp.DappType;
import com.iexec.common.replicate.ReplicateDetails;
import com.iexec.common.replicate.ReplicateStatus;
import com.iexec.common.security.Signature;
import com.iexec.common.task.TaskDescription;
import com.iexec.common.utils.SignatureUtils;
import com.iexec.worker.chain.ContributionService;
import com.iexec.worker.chain.IexecHubService;
import com.iexec.worker.chain.RevealService;
import com.iexec.worker.chain.Web3jService;
import com.iexec.worker.config.PublicConfigurationService;
import com.iexec.worker.config.WorkerConfigurationService;
import com.iexec.worker.dataset.DatasetService;
import com.iexec.worker.docker.DockerComputationService;
import com.iexec.worker.feign.CustomFeignClient;
import com.iexec.worker.result.ResultService;
import com.iexec.worker.sms.SmsService;
import com.iexec.worker.utils.LoggingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import static com.iexec.common.replicate.ReplicateStatus.*;


/*
 * this service is only caller by ReplicateDemandService when getting new replicate
 * or by AmnesiaRecoveryService when recovering an interrupted task
 */
@Slf4j
@Service
public class TaskExecutorService {

    // external services
    private DatasetService datasetService;
    private DockerComputationService dockerComputationService;
    private ResultService resultService;
    private ContributionService contributionService;
    private CustomFeignClient customFeignClient;
    private RevealService revealService;
    private WorkerConfigurationService workerConfigurationService;
    private IexecHubService iexecHubService;
    private SmsService smsService;
    private Web3jService web3jService;
    private PublicConfigurationService publicConfigurationService;

    // internal variables
    private int maxNbExecutions;
    private ThreadPoolExecutor executor;
    private String corePublicAddress;

    //TODO make this fat constructor lose weight
    public TaskExecutorService(DatasetService datasetService,
                               DockerComputationService dockerComputationService,
                               ResultService resultService,
                               ContributionService contributionService,
                               CustomFeignClient customFeignClient,
                               RevealService revealService,
                               WorkerConfigurationService workerConfigurationService,
                               IexecHubService iexecHubService,
                               SmsService smsService,
                               Web3jService web3jService,
                               PublicConfigurationService publicConfigurationService) {
        this.datasetService = datasetService;
        this.dockerComputationService = dockerComputationService;
        this.resultService = resultService;
        this.contributionService = contributionService;
        this.customFeignClient = customFeignClient;
        this.revealService = revealService;
        this.workerConfigurationService = workerConfigurationService;
        this.iexecHubService = iexecHubService;
        this.smsService = smsService;
        this.web3jService = web3jService;
        this.publicConfigurationService = publicConfigurationService;

        maxNbExecutions = Runtime.getRuntime().availableProcessors() - 1;
        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(maxNbExecutions);
    }

    @PostConstruct
    public void initIt() {
        corePublicAddress = publicConfigurationService.getSchedulerPublicAddress();
    }

    public boolean canAcceptMoreReplicates() {
        return executor.getActiveCount() < maxNbExecutions;
    }

    public CompletableFuture<Void> addReplicate(ContributionAuthorization contributionAuth) {

        String chainTaskId = contributionAuth.getChainTaskId();

        Optional<TaskDescription> taskDescriptionFromChain = iexecHubService.getTaskDescriptionFromChain(chainTaskId);

        return CompletableFuture.supplyAsync(() -> compute(contributionAuth), executor)
                .thenApply(stdout -> resultService.saveResult(chainTaskId, taskDescriptionFromChain.get(), stdout))
                .thenAccept(isSaved -> {
                    if (isSaved) contribute(contributionAuth);
                })
                .handle((res, err) -> {
                    if (err != null) {
                        err.printStackTrace();
                    }
                    return res;
                });
    }


    public void tryToContribute(ContributionAuthorization contributionAuth) {

        String chainTaskId = contributionAuth.getChainTaskId();

        if (!contributionService.isContributionAuthorizationValid(contributionAuth, corePublicAddress)) {
            log.error("The contribution contribAuth is NOT valid, the task will not be performed"
                    + " [chainTaskId:{}, contribAuth:{}]", chainTaskId, contributionAuth);
            return;
        }

        boolean isResultAvailable = resultService.isResultAvailable(chainTaskId);

        if (!isResultAvailable) {
            log.info("Result not found, will restart task from RUNNING [chainTaskId:{}]", chainTaskId);
            addReplicate(contributionAuth);
        } else {
            log.info("Result found, will restart task from CONTRIBUTING [chainTaskId:{}]", chainTaskId);
            contribute(contributionAuth);
        }
    }

    @Async
    private String compute(ContributionAuthorization contributionAuth) {
        String chainTaskId = contributionAuth.getChainTaskId();
        String message = "";

        if (!contributionService.isChainTaskInitialized(chainTaskId)) {
            log.error("Task not initialized onchain yet [ChainTaskId:{}]", chainTaskId);
            throw new IllegalArgumentException("Task not initialized onchain yet");
        }

        Optional<TaskDescription> taskDescriptionFromChain = iexecHubService.getTaskDescriptionFromChain(chainTaskId);

        if (!taskDescriptionFromChain.isPresent()) {
            message = "AvailableReplicateModel not found";
            log.error(message + " [chainTaskId:{}]", chainTaskId);
            return message;
        }

        TaskDescription taskDescription = taskDescriptionFromChain.get();

        // if (TeeEnabled && no Tee supported) => return;
        boolean doesTaskNeedTee = taskDescription.isTrustedExecution();
        if (doesTaskNeedTee && !workerConfigurationService.isTeeEnabled()) {
            throw new UnsupportedOperationException("Task needs TEE, I don't support it");
        }

        // check app type
        customFeignClient.updateReplicateStatus(chainTaskId, RUNNING);
        if (!taskDescription.getAppType().equals(DappType.DOCKER)) {
            message = "Application is not of type Docker";
            log.error(message + " [chainTaskId:{}]", chainTaskId);
            return message;
        }

        // Try to downloadApp
        String errorDwnlApp = tryToDownloadApp(taskDescription);
        if (!errorDwnlApp.isEmpty()) {
            return errorDwnlApp;
        }
        customFeignClient.updateReplicateStatus(chainTaskId, APP_DOWNLOADED);

        // Try to downloadData
        String errorDwnlData = tryToDownloadData(taskDescription);
        if (!errorDwnlData.isEmpty()) {
            return errorDwnlData;
        }
        customFeignClient.updateReplicateStatus(chainTaskId, DATA_DOWNLOADED);

        boolean isFetched = smsService.fetchTaskSecrets(contributionAuth);
        if (!isFetched) {
            log.warn("No secrets fetched for this task, continuing [chainTaskId:{}]:", chainTaskId);
        }

        customFeignClient.updateReplicateStatus(chainTaskId, COMPUTING);

        // decrypt data
        boolean isDatasetDecryptionNeeded = datasetService.isDatasetDecryptionNeeded(chainTaskId);
        boolean isDatasetDecrypted = false;

        if (isDatasetDecryptionNeeded) {
            isDatasetDecrypted = datasetService.decryptDataset(chainTaskId, taskDescription.getDatasetUri());
        }

        if (isDatasetDecryptionNeeded && !isDatasetDecrypted) {
            customFeignClient.updateReplicateStatus(chainTaskId, COMPUTE_FAILED);
            message = "Failed to decrypt dataset, URI:" + taskDescription.getDatasetUri();
            log.error(message + " [chainTaskId:{}]", chainTaskId);
            return message;
        }

        String error = checkContributionAbility(chainTaskId);
        if (!error.isEmpty()) {
            return error;
        }

        // compute
        String datasetFilename = datasetService.getDatasetFilename(taskDescription.getDatasetUri());
        message = dockerComputationService.dockerRunAndGetLogs(taskDescription, datasetFilename);

        if (message.isEmpty()) {
            customFeignClient.updateReplicateStatus(chainTaskId, COMPUTE_FAILED);
            message = "Failed to start computation";
            log.error(message + " [chainTaskId:{}]", chainTaskId);
            return message;
        }

        customFeignClient.updateReplicateStatus(chainTaskId, COMPUTED);
        return message;
    }

    private String tryToDownloadApp(TaskDescription taskDescription) {
        String chainTaskId = taskDescription.getChainTaskId();

        String error = checkContributionAbility(chainTaskId);
        if (!error.isEmpty()) {
            return error;
        }

        // pull app
        customFeignClient.updateReplicateStatus(chainTaskId, APP_DOWNLOADING);
        boolean isAppDownloaded = dockerComputationService.dockerPull(chainTaskId, taskDescription.getAppUri());
        if (!isAppDownloaded) {
            customFeignClient.updateReplicateStatus(chainTaskId, APP_DOWNLOAD_FAILED);
            String errorMessage = "Failed to pull application image, URI:" + taskDescription.getAppUri();
            log.error(errorMessage + " [chainTaskId:{}]", chainTaskId);
            return errorMessage;
        }

        return "";
    }

    private String tryToDownloadData(TaskDescription taskDescription) {
        String chainTaskId = taskDescription.getChainTaskId();

        String error = checkContributionAbility(chainTaskId);
        if (!error.isEmpty()) {
            return error;
        }

        // pull data
        customFeignClient.updateReplicateStatus(chainTaskId, DATA_DOWNLOADING);
        boolean isDatasetDownloaded = datasetService.downloadDataset(chainTaskId, taskDescription.getDatasetUri());
        if (!isDatasetDownloaded) {
            customFeignClient.updateReplicateStatus(chainTaskId, DATA_DOWNLOAD_FAILED);
            String errorMessage = "Failed to pull dataset, URI:" + taskDescription.getDatasetUri();
            log.error(errorMessage + " [chainTaskId:{}]", chainTaskId);
            return errorMessage;
        }

        return "";
    }

    private String checkContributionAbility(String chainTaskId) {
        String errorMessage = "";

        Optional<ReplicateStatus> contributionStatus = contributionService.getCanContributeStatus(chainTaskId);
        if(contributionStatus.isPresent() && !contributionStatus.get().equals(CAN_CONTRIBUTE)) {
            errorMessage = "The worker cannot contribute";
            log.error(errorMessage + " [chainTaskId:{}, replicateStatus:{}]", chainTaskId, contributionStatus.get());
            customFeignClient.updateReplicateStatus(chainTaskId, contributionStatus.get());
            return errorMessage;
        }

        return errorMessage;
    }

    @Async
    public void contribute(ContributionAuthorization contribAuth) {
        String chainTaskId = contribAuth.getChainTaskId();
        String deterministHash = resultService.getDeterministHashForTask(chainTaskId);
        Optional<Signature> oEnclaveSignature = resultService.getEnclaveSignatureFromFile(chainTaskId);

        if (deterministHash.isEmpty()) {
            return;
        }

        Signature enclaveSignature = SignatureUtils.emptySignature();

        if (oEnclaveSignature.isPresent()) {
            enclaveSignature = contributionService.getEnclaveSignature(contribAuth, deterministHash, oEnclaveSignature.get());
        }

        Optional<ReplicateStatus> canContributeStatus = contributionService.getCanContributeStatus(chainTaskId);
        if (!canContributeStatus.isPresent()) {
            log.error("canContributeStatus should not be empty (getChainTask issue) [chainTaskId:{}]", chainTaskId);
            return;
        }

        customFeignClient.updateReplicateStatus(chainTaskId, canContributeStatus.get());
        if (!canContributeStatus.get().equals(CAN_CONTRIBUTE) & enclaveSignature != null) {
            log.warn("Cant contribute [chainTaskId:{}, status:{}]", chainTaskId, canContributeStatus.get());
            return;
        }

        if (!contributionService.hasEnoughGas()) {
            customFeignClient.updateReplicateStatus(chainTaskId, OUT_OF_GAS);
            String noEnoughGas = String.format("Out of gas! please refill your wallet [walletAddress:%s]",
                    contribAuth.getWorkerWallet());
            LoggingUtils.printHighlightedMessage(noEnoughGas);
            System.exit(0);
        }

        customFeignClient.updateReplicateStatus(chainTaskId, CONTRIBUTING);

        Optional<ChainReceipt> oChainReceipt = contributionService.contribute(contribAuth, deterministHash, enclaveSignature);
        if (!oChainReceipt.isPresent()) {
            log.warn("The chain receipt of the contribution is empty, nothing will be sent to the core [chainTaskId:{}]", chainTaskId);
            return;
        }

        if (oChainReceipt.get().getBlockNumber() == 0) {
            log.warn("The blocknumber of the receipt is equal to 0, the CONTRIBUTED status will not be " +
                    "sent to the core [chainTaskId:{}]", chainTaskId);
            return;
        }

        customFeignClient.updateReplicateStatus(chainTaskId, CONTRIBUTED,
                ReplicateDetails.builder().chainReceipt(oChainReceipt.get()).build());
    }

    @Async
    public void reveal(String chainTaskId, long consensusBlock) {
        log.info("Trying to reveal [chainTaskId:{}]", chainTaskId);
        if (!web3jService.isBlockAvailable(consensusBlock)) {
            log.warn("Sync issues before canReveal (latestBlock before consensusBlock) [chainTaskId:{}, latestBlock:{}, " +
                    "consensusBlock:{}]", chainTaskId, web3jService.getLatestBlockNumber(), consensusBlock);
            return;
        }

        if (!revealService.canReveal(chainTaskId)) {
            log.warn("The worker will not be able to reveal [chainTaskId:{}]", chainTaskId);
            customFeignClient.updateReplicateStatus(chainTaskId, CANT_REVEAL);
            return;
        }

        if (!revealService.hasEnoughGas()) {
            customFeignClient.updateReplicateStatus(chainTaskId, OUT_OF_GAS);
            customFeignClient.updateReplicateStatus(chainTaskId, OUT_OF_GAS);
            String noEnoughGas = String.format("Out of gas! please refill your wallet [walletAddress:%s]",
                    workerConfigurationService.getWorkerWalletAddress());
            LoggingUtils.printHighlightedMessage(noEnoughGas);
            System.exit(0);
        }

        customFeignClient.updateReplicateStatus(chainTaskId, REVEALING);

        Optional<ChainReceipt> optionalChainReceipt = revealService.reveal(chainTaskId);
        if (!optionalChainReceipt.isPresent()) {
            log.warn("The chain receipt of the reveal is empty, nothing will be sent to the core [chainTaskId:{}]", chainTaskId);
            return;
        }

        if (optionalChainReceipt.get().getBlockNumber() == 0) {
            log.warn("The blocknumber of the receipt is equal to 0, the REVEALED status will not be " +
                    "sent to the core [chainTaskId:{}]", chainTaskId);
            return;
        }

        customFeignClient.updateReplicateStatus(chainTaskId, REVEALED,
                ReplicateDetails.builder().chainReceipt(optionalChainReceipt.get()).build());
    }

    @Async
    public void abortConsensusReached(String chainTaskId) {
        resultService.removeResult(chainTaskId);
        customFeignClient.updateReplicateStatus(chainTaskId, ABORTED_ON_CONSENSUS_REACHED);
    }

    @Async
    public void abortContributionTimeout(String chainTaskId) {
        resultService.removeResult(chainTaskId);
        customFeignClient.updateReplicateStatus(chainTaskId, ABORTED_ON_CONTRIBUTION_TIMEOUT);
    }

    @Async
    public void uploadResult(String chainTaskId) {
        customFeignClient.updateReplicateStatus(chainTaskId, RESULT_UPLOADING);

        boolean isResultEncryptionNeeded = resultService.isResultEncryptionNeeded(chainTaskId);
        boolean isResultEncrypted = false;

        if (isResultEncryptionNeeded) {
            isResultEncrypted = resultService.encryptResult(chainTaskId);
        }

        if (isResultEncryptionNeeded && !isResultEncrypted) {
            customFeignClient.updateReplicateStatus(chainTaskId, RESULT_UPLOAD_FAILED);
            log.error("Failed to encrypt result [chainTaskId:{}]", chainTaskId);
            return;
        }

        String resultLink = resultService.uploadResult(chainTaskId);

        if (resultLink.isEmpty()) {
            log.error("ResultLink missing (aborting) [chainTaskId:{}]", chainTaskId);
            customFeignClient.updateReplicateStatus(chainTaskId, RESULT_UPLOAD_FAILED);
            return;
        }

        String callbackData = resultService.getCallbackDataFromFile(chainTaskId);

        log.info("Uploaded result with details [chainTaskId:{}, resultLink:{}, callbackData:{}]",
                chainTaskId, resultLink, callbackData);

        ReplicateDetails details = ReplicateDetails.builder()
                .resultLink(resultLink)
                .chainCallbackData(callbackData)
                .build();

        customFeignClient.updateReplicateStatus(chainTaskId, RESULT_UPLOADED, details);
    }

    @Async
    public void completeTask(String chainTaskId) {
        resultService.removeResult(chainTaskId);
        customFeignClient.updateReplicateStatus(chainTaskId, COMPLETED);
    }
}