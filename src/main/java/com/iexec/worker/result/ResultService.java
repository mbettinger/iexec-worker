package com.iexec.worker.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iexec.common.replicate.AvailableReplicateModel;
import com.iexec.common.result.ResultModel;
import com.iexec.common.result.eip712.Eip712Challenge;
import com.iexec.common.result.eip712.Eip712ChallengeUtils;
import com.iexec.common.security.Signature;
import com.iexec.common.utils.BytesUtils;
import com.iexec.worker.chain.CredentialsService;
import com.iexec.worker.config.PublicConfigurationService;
import com.iexec.worker.config.WorkerConfigurationService;
import com.iexec.worker.feign.ResultRepoClient;
import com.iexec.worker.security.TeeSignature;
import com.iexec.worker.utils.FileHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.iexec.common.utils.BytesUtils.bytesToString;
import static com.iexec.common.utils.BytesUtils.stringToBytes;
import static com.iexec.worker.utils.FileHelper.createFileWithContent;

@Slf4j
@Service
public class ResultService {

    private static final String DETERMINIST_FILE_NAME = "consensus.iexec";
    private static final String TEE_ENCLAVE_SIGNATURE_FILE_NAME = "enclaveSig.iexec";
    private static final String CALLBACK_FILE_NAME = "callback.iexec";
    private static final String STDOUT_FILENAME = "stdout.txt";
    private final PublicConfigurationService publicConfigurationService;
    private final CredentialsService credentialsService;
    private final ResultRepoClient resultRepoClient;
    private Map<String, ResultInfo> resultInfoMap;
    private WorkerConfigurationService configurationService;

    public ResultService(WorkerConfigurationService configurationService,
                         PublicConfigurationService publicConfigurationService,
                         CredentialsService credentialsService,
                         ResultRepoClient resultRepoClient) {
        this.configurationService = configurationService;
        this.publicConfigurationService = publicConfigurationService;
        this.credentialsService = credentialsService;
        this.resultRepoClient = resultRepoClient;
        this.resultInfoMap = new ConcurrentHashMap<>();
    }

    public boolean saveResult(String chainTaskId, AvailableReplicateModel replicateModel, String stdout) {
        try {
            saveStdoutFileInResultFolder(chainTaskId, stdout);
            zipResultFolder(chainTaskId);
            saveResultInfo(chainTaskId, replicateModel);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private File saveStdoutFileInResultFolder(String chainTaskId, String stdoutContent) {
        log.info("Stdout file added to result folder [chainTaskId:{}]", chainTaskId);
        String filePath = getResultFolderPath(chainTaskId) + File.separator + STDOUT_FILENAME;
        return createFileWithContent(filePath, stdoutContent);
    }

    public void zipResultFolder(String chainTaskId) {
        File zipFile = FileHelper.zipFolder(getResultFolderPath(chainTaskId));
        log.info("Zip file has been created [chainTaskId:{}, zipFile:{}]", chainTaskId, zipFile.getAbsolutePath());
    }

    public void saveResultInfo(String chainTaskId, AvailableReplicateModel replicateModel) {
        ResultInfo resultInfo = ResultInfo.builder()
                .image(replicateModel.getAppUri())
                .cmd(replicateModel.getCmd())
                .deterministHash(getDeterministHashFromFile(chainTaskId))
                .datasetUri(replicateModel.getDatasetUri())
                .build();

        resultInfoMap.put(chainTaskId, resultInfo);
    }

    public ResultModel getResultModelWithZip(String chainTaskId) {
        ResultInfo resultInfo = getResultInfos(chainTaskId);
        byte[] zipResultAsBytes = new byte[0];
        String zipLocation = getResultZipFilePath(chainTaskId);
        try {
            zipResultAsBytes = Files.readAllBytes(Paths.get(zipLocation));
        } catch (IOException e) {
            log.error("Failed to get zip result [chainTaskId:{}, zipLocation:{}]", chainTaskId, zipLocation);
        }

        return ResultModel.builder()
                .chainTaskId(chainTaskId)
                .image(resultInfo.getImage())
                .cmd(resultInfo.getCmd())
                .zip(zipResultAsBytes)
                .deterministHash(resultInfo.getDeterministHash())
                .build();
    }

    public ResultInfo getResultInfos(String chainTaskId) {
        return resultInfoMap.get(chainTaskId);
    }

    public String getResultZipFilePath(String chainTaskId) {
        return getResultFolderPath(chainTaskId) + ".zip";
    }

    public String getResultFolderPath(String chainTaskId) {
        return configurationService.getResultBaseDir() + File.separator + chainTaskId + FileHelper.SLASH_OUTPUT;
    }

    public boolean isResultZipFound(String chainTaskId) {
        return new File(getResultZipFilePath(chainTaskId)).exists();
    }

    public boolean isResultFolderFound(String chainTaskId) {
        return new File(getResultFolderPath(chainTaskId)).exists();
    }

    public boolean removeResult(String chainTaskId) {
        boolean deletedInMap = resultInfoMap.remove(chainTaskId) != null;
        boolean deletedTaskFolder = FileHelper.deleteFolder(new File(getResultFolderPath(chainTaskId)).getParent());

        boolean deleted = deletedInMap && deletedTaskFolder;
        if (deletedTaskFolder) {
            log.info("The result of the chainTaskId has been deleted [chainTaskId:{}]", chainTaskId);
        } else {
            log.warn("The result of the chainTaskId couldn't be deleted [chainTaskId:{}, deletedInMap:{}, " +
                            "deletedTaskFolder:{}]",
                    chainTaskId, deletedInMap, deletedTaskFolder);
        }

        return deleted;
    }

    public void cleanUnusedResultFolders(List<String> recoveredTasks) {
        for (String chainTaskId : getAllChainTaskIdsInResultFolder()) {
            if (!recoveredTasks.contains(chainTaskId)) {
                removeResult(chainTaskId);
            }
        }
    }

    public List<String> getAllChainTaskIdsInResultFolder() {
        File resultsFolder = new File(configurationService.getResultBaseDir());
        String[] chainTaskIdFolders = resultsFolder.list((current, name) -> new File(current, name).isDirectory());

        if (chainTaskIdFolders == null || chainTaskIdFolders.length == 0) {
            return Collections.emptyList();
        }
        return Arrays.asList(chainTaskIdFolders);
    }

    public String getDeterministHashFromFile(String chainTaskId) {
        String hash = "";
        try {
            String deterministFilePathName = getResultFolderPath(chainTaskId) + FileHelper.SLASH_IEXEC_OUT + File.separator + DETERMINIST_FILE_NAME;
            Path deterministFilePath = Paths.get(deterministFilePathName);

            if (deterministFilePath.toFile().exists()) {
                byte[] content = Files.readAllBytes(deterministFilePath);
                hash = bytesToString(Hash.sha256(content));
                log.info("The determinist file exists and its hash has been computed [chainTaskId:{}, hash:{}]", chainTaskId, hash);
                return hash;
            } else {
                log.info("No determinist file exists [chainTaskId:{}]", chainTaskId);
            }

            String resultFilePathName = getResultZipFilePath(chainTaskId);
            byte[] content = Files.readAllBytes(Paths.get(resultFilePathName));
            hash = bytesToString(Hash.sha256(content));
            log.info("The hash of the result file will be used instead [chainTaskId:{}, hash:{}]", chainTaskId, hash);
        } catch (IOException e) {
            e.printStackTrace();
            log.error("Failed to getDeterministHashFromFile [chainTaskId:{}]", chainTaskId);
        }

        return hash;
    }

    public String getCallbackDataFromFile(String chainTaskId) {
        String hexaString = "";
        try {
            String callbackFilePathName = getResultFolderPath(chainTaskId) + FileHelper.SLASH_IEXEC_OUT + File.separator + CALLBACK_FILE_NAME;
            Path callbackFilePath = Paths.get(callbackFilePathName);

            if (callbackFilePath.toFile().exists()) {
                byte[] callbackFileBytes = Files.readAllBytes(callbackFilePath);
                hexaString = new String(callbackFileBytes);
                boolean isHexaString = BytesUtils.isHexaString(hexaString);
                log.info("Callback file exists [chainTaskId:{}, hexaString:{}, isHexaString:{}]", chainTaskId, hexaString, isHexaString);
                return isHexaString ? hexaString : "";
            } else {
                log.info("No callback file [chainTaskId:{}]", chainTaskId);
            }
        } catch (IOException e) {
            e.printStackTrace();
            log.error("Failed to getCallbackDataFromFile [chainTaskId:{}]", chainTaskId);
        }

        return hexaString;
    }

    public Optional<Signature> getEnclaveSignatureFromFile(String chainTaskId) {
        String executionEnclaveSignatureFileName = getResultFolderPath(chainTaskId) + FileHelper.SLASH_IEXEC_OUT + File.separator + TEE_ENCLAVE_SIGNATURE_FILE_NAME;
        Path executionEnclaveSignatureFilePath = Paths.get(executionEnclaveSignatureFileName);

        if (!executionEnclaveSignatureFilePath.toFile().exists()) {
            log.info("TeeSignature file doesn't exist [chainTaskId:{}]", chainTaskId);
            return Optional.empty();
        }

        ObjectMapper mapper = new ObjectMapper();
        TeeSignature teeSignature = null;
        try {
            teeSignature = mapper.readValue(executionEnclaveSignatureFilePath.toFile(), TeeSignature.class);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (teeSignature == null) {
            log.info("TeeSignature file exits but parsing failed [chainTaskId:{}]", chainTaskId);
            return Optional.empty();
        }

        Signature sign = teeSignature.getSign();
        log.info("TeeSignature file exists [chainTaskId:{}, r:{}, sign:{}, v:{}]",
                chainTaskId, sign.getR(), sign.getS(), sign.getV());
        return Optional.of(sign);
    }


    public String uploadResult(String chainTaskId) {
        String resultLink = "";
        Eip712Challenge eip712Challenge = resultRepoClient.getChallenge(publicConfigurationService.getChainId());
        ECKeyPair ecKeyPair = credentialsService.getCredentials().getEcKeyPair();
        String authorizationToken = Eip712ChallengeUtils.buildAuthorizationToken(eip712Challenge,
                credentialsService.getCredentials().getAddress(), ecKeyPair);

        ResponseEntity<String> responseEntity = resultRepoClient.uploadResult(authorizationToken,
                getResultModelWithZip(chainTaskId));

        if (responseEntity != null && responseEntity.getStatusCode().is2xxSuccessful()) {
            resultLink = responseEntity.getBody();
        }

        return resultLink;
    }
}
