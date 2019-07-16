package com.iexec.worker.feign;

import com.iexec.common.result.ResultModel;
import com.iexec.common.result.eip712.Eip712Challenge;
import feign.FeignException;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@FeignClient(name = "ResultRepoClient",
        url = "#{publicConfigurationService.resultRepositoryURL}",
        configuration = FeignConfiguration.class)
public interface ResultRepoClient {

    @GetMapping("/results/challenge")
    Eip712Challenge getChallenge(@RequestParam(name = "chainId") Integer chainId) throws FeignException;

    @PostMapping("/results")
    ResponseEntity<String> uploadResult(@RequestHeader("Authorization") String customToken,
                                        @RequestBody ResultModel resultModel);

}