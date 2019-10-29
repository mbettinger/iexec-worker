package com.iexec.worker.chain;

import com.iexec.common.chain.ContributionAuthorization;
import com.iexec.common.utils.BytesUtils;
import com.iexec.common.utils.HashUtils;
import com.iexec.common.utils.SignatureUtils;
import com.iexec.worker.config.PublicConfigurationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
@Service
public class ContributionAuthorizationService {

    private PublicConfigurationService publicConfigurationService;
    private Map<String, ContributionAuthorization> contributionAuthorizations;
    private String corePublicAddress;

    public ContributionAuthorizationService(PublicConfigurationService publicConfigurationService) {
        this.publicConfigurationService = publicConfigurationService;
    }

    @PostConstruct
    public void initIt() {
        corePublicAddress = publicConfigurationService.getSchedulerPublicAddress();
        contributionAuthorizations = new ConcurrentHashMap<>();
    }


    public boolean isContributionAuthorizationValid(ContributionAuthorization auth, String signerAddress) {
        // create the hash that was used in the signature in the core
        byte[] message = BytesUtils.stringToBytes(
                HashUtils.concatenateAndHash(auth.getWorkerWallet(), auth.getChainTaskId(), auth.getEnclaveChallenge()));

        return SignatureUtils.isSignatureValid(message, auth.getSignature(), signerAddress);
    }

    public boolean putContributionAuthorization(ContributionAuthorization contributionAuthorization) {
        if (contributionAuthorization == null || contributionAuthorization.getChainTaskId() == null){
            log.error("Cant putContributionAuthorization (null) [contributionAuthorization:{}]", contributionAuthorization);
            return false;
        }

        if (!isContributionAuthorizationValid(contributionAuthorization, corePublicAddress)){
            log.error("Cant putContributionAuthorization (invalid) [contributionAuthorization:{}]", contributionAuthorization);
            return false;
        }
        contributionAuthorizations.putIfAbsent(contributionAuthorization.getChainTaskId(), contributionAuthorization);
        return true;
    }

    public ContributionAuthorization getContributionAuthorization(String chainTaskId) {
        return contributionAuthorizations.get(chainTaskId);
    }

}
