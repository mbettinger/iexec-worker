package com.iexec.worker.feign;


import com.iexec.common.config.PublicConfiguration;
import com.iexec.common.config.WorkerConfigurationModel;
import com.iexec.common.security.Signature;
import feign.FeignException;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@FeignClient(name = "WorkerClient",
        url = "${core.protocol}://${core.host}:${core.port}",
        configuration = FeignConfiguration.class)
public interface WorkerClient {

    @GetMapping("/workers/config")
    PublicConfiguration getPublicConfiguration() throws FeignException;

    @PostMapping("/workers/ping")
    String ping(@RequestHeader("Authorization") String bearerToken) throws FeignException;

    @PostMapping("/workers/register")
    void registerWorker(@RequestHeader("Authorization") String bearerToken,
                        @RequestBody WorkerConfigurationModel model) throws FeignException;

    @GetMapping("/workers/currenttasks")
    List<String> getCurrentTasks(@RequestHeader("Authorization") String bearerToken) throws FeignException;

    @PostMapping("/workers/login")
    String login(@RequestParam(name = "walletAddress") String walletAddress,
                 @RequestBody Signature authorization) throws FeignException;

    @GetMapping("/workers/challenge")
    String getChallenge(@RequestParam(name = "walletAddress") String walletAddress) throws FeignException;

}