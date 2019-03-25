package com.iexec.worker.feign;

import com.iexec.common.chain.ChainReceipt;
import com.iexec.common.chain.ContributionAuthorization;
import com.iexec.common.config.PublicConfiguration;
import com.iexec.common.config.WorkerConfigurationModel;
import com.iexec.common.disconnection.InterruptedReplicateModel;
import com.iexec.common.replicate.ReplicateDetails;
import com.iexec.common.replicate.ReplicateStatus;
import com.iexec.common.result.eip712.Eip712Challenge;
import com.iexec.common.security.Signature;
import com.iexec.common.utils.SignatureUtils;
import com.iexec.worker.chain.CredentialsService;
import com.iexec.worker.config.CoreConfigurationService;
import com.iexec.worker.config.PublicConfigurationService;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.web3j.crypto.ECKeyPair;

import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class CustomFeignClient {


    private static final int RETRY_TIME = 5000;
    private static final String TOKEN_PREFIX = "Bearer ";
    private final String url;

    private CoreClient coreClient;
    private WorkerClient workerClient;
    private ReplicateClient replicateClient;
    private ResultRepoClient resultRepoClient;
    private PublicConfigurationService publicConfigurationService;
    private CredentialsService credentialsService;
    private String currentToken;

    public CustomFeignClient(CoreClient coreClient,
                             WorkerClient workerClient,
                             ReplicateClient replicateClient,
                             CredentialsService credentialsService,
                             PublicConfigurationService publicConfigurationService,
                             CoreConfigurationService coreConfigurationService) {
        this.coreClient = coreClient;
        this.workerClient = workerClient;
        this.replicateClient = replicateClient;
        this.publicConfigurationService = publicConfigurationService;
        this.credentialsService = credentialsService;
        this.url = coreConfigurationService.getUrl();
        this.currentToken = "";
    }

    public PublicConfiguration getPublicConfiguration() {
        try {
            return workerClient.getPublicConfiguration();
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to getPublicConfiguration, will retry");
                sleep();
                return getPublicConfiguration();
            }
        }
        return null;
    }

    public String getCoreVersion() {
        try {
            return coreClient.getCoreVersion();
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to getCoreVersion, will retry");
                sleep();
                return getCoreVersion();
            }
        }
        return null;
    }

    public String ping() {
        try {
            return workerClient.ping(getToken());
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to ping [instance:{}]", url);
            } else if (HttpStatus.valueOf(e.status()).equals(HttpStatus.UNAUTHORIZED)) {
                generateNewToken();
                return workerClient.ping(getToken());
            }
        }

        return "";
    }

    public void registerWorker(WorkerConfigurationModel model) {
        try {
            workerClient.registerWorker(getToken(), model);
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to registerWorker, will retry [instance:{}]", url);
                sleep();
                registerWorker(model);
            } else if (HttpStatus.valueOf(e.status()).equals(HttpStatus.UNAUTHORIZED)) {
                generateNewToken();
                workerClient.registerWorker(getToken(), model);
            }
        }
    }

    public List<InterruptedReplicateModel> getInterruptedReplicates(long lastAvailableBlockNumber) {
        try {
            return replicateClient.getInterruptedReplicates(lastAvailableBlockNumber, getToken());
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to getInterruptedReplicates, will retry [instance:{}]", url);
                sleep();
                return getInterruptedReplicates(lastAvailableBlockNumber);
            } else if (HttpStatus.valueOf(e.status()).equals(HttpStatus.UNAUTHORIZED)) {
                generateNewToken();
                return getInterruptedReplicates(lastAvailableBlockNumber);
            }
        }
        return null;
	}

    public List<String> getTasksInProgress(){
        try {
            return workerClient.getCurrentTasks(getToken());
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to get tasks in progress, will retry [instance:{}]", url);
                sleep();
            } else if (HttpStatus.valueOf(e.status()).equals(HttpStatus.UNAUTHORIZED)) {
                generateNewToken();
                return workerClient.getCurrentTasks(getToken());
            }
        }

        return Collections.emptyList();
    }

    public ContributionAuthorization getAvailableReplicate(long lastAvailableBlockNumber) {
        try {
            return replicateClient.getAvailableReplicate(lastAvailableBlockNumber, getToken());
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to getAvailableReplicate [instance:{}]", url);
            } else if (HttpStatus.valueOf(e.status()).equals(HttpStatus.UNAUTHORIZED)) {
                generateNewToken();
                return replicateClient.getAvailableReplicate(lastAvailableBlockNumber, getToken());
            }
        }
        return null;
    }

    // TODO: those next 4 methods need to be refactored
    public void updateReplicateStatus(String chainTaskId, ReplicateStatus status) {
        updateReplicateStatus(chainTaskId, status, null, "");
    }

    public void updateReplicateStatus(String chainTaskId, ReplicateStatus status, String resultLink) {
        updateReplicateStatus(chainTaskId, status, null, resultLink);
    }

    public void updateReplicateStatus(String chainTaskId, ReplicateStatus status, ChainReceipt chainReceipt) {
        updateReplicateStatus(chainTaskId, status, chainReceipt, "");
    }

    public void updateReplicateStatus(String chainTaskId, ReplicateStatus status, ChainReceipt chainReceipt, String resultLink) {
        log.info(status.toString() + " [chainTaskId:{}]", chainTaskId);

        ReplicateDetails details = ReplicateDetails.builder()
                .chainReceipt(chainReceipt)
                .resultLink(resultLink)
                .build();

        try {
            replicateClient.updateReplicateStatus(chainTaskId, status, getToken(), details);
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to updateReplicateStatus, will retry [instance:{}]", url);
                sleep();
                updateReplicateStatus(chainTaskId, status, chainReceipt, resultLink);
                return;
            }

            if (HttpStatus.valueOf(e.status()).equals(HttpStatus.UNAUTHORIZED)) {
                generateNewToken();
                log.info(status.toString() + " [chainTaskId:{}]", chainTaskId);
                replicateClient.updateReplicateStatus(chainTaskId, status, getToken(), details);
            }
        }
    }

    private String getCoreChallenge(String workerAddress) {
        try {
            return workerClient.getChallenge(workerAddress);
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to getCoreChallenge, will retry [instance:{}]", url);
                sleep();
                return getCoreChallenge(workerAddress);
            }
        }
        return null;
    }

    public Eip712Challenge getResultRepoChallenge() {
        try {
            return resultRepoClient.getChallenge(publicConfigurationService.getChainId());
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to getResultRepoChallenge, will retry [instance:{}]", url);
                sleep();
                return getResultRepoChallenge();
            }
        }
        return null;
    }

    private String login(String workerAddress, Signature signature) {
        try {
            return workerClient.login(workerAddress, signature);
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to login, will retry [instance:{}]", url);
                sleep();
                return login(workerAddress, signature);
            }
        }
        return null;
    }

    private void sleep() {
        try {
            Thread.sleep(RETRY_TIME);
        } catch (InterruptedException e) {
        }
    }

    private String getToken() {
        if (currentToken.isEmpty()) {
            String workerAddress = credentialsService.getCredentials().getAddress();
            ECKeyPair ecKeyPair = credentialsService.getCredentials().getEcKeyPair();
            String challenge = getCoreChallenge(workerAddress);

            Signature signature = SignatureUtils.hashAndSign(challenge, workerAddress, ecKeyPair);
            currentToken = TOKEN_PREFIX + login(workerAddress, signature);
        }

        return currentToken;
    }

    private void expireToken() {
        currentToken = "";
    }

    private String generateNewToken() {
        expireToken();
        return getToken();
    }

}
