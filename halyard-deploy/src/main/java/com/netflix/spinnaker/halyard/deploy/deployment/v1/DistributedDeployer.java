/*
 * Copyright 2017 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.halyard.deploy.deployment.v1;

import com.netflix.spinnaker.halyard.config.model.v1.node.Account;
import com.netflix.spinnaker.halyard.core.DaemonResponse;
import com.netflix.spinnaker.halyard.core.RemoteAction;
import com.netflix.spinnaker.halyard.core.error.v1.HalException;
import com.netflix.spinnaker.halyard.core.problem.v1.Problem;
import com.netflix.spinnaker.halyard.core.tasks.v1.DaemonTaskHandler;
import com.netflix.spinnaker.halyard.deploy.services.v1.GenerateService.ResolvedConfiguration;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.RunningServiceDetails;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.SpinnakerRuntimeSettings;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.*;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.OrcaService.Orca;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.OrcaService.Orca.ActiveExecutions;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.RoscoService.Rosco;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.distributed.DistributedService;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.distributed.DistributedServiceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Component
@Slf4j
public class DistributedDeployer<T extends Account> implements Deployer<DistributedServiceProvider<T>, AccountDeploymentDetails<T>> {
  @Autowired
  OrcaRunner orcaRunner;

  @Value("${deploy.maxRemainingServerGroups:2}")
  private Integer MAX_REMAINING_SERVER_GROUPS;

  @Override
  public void rollback(DistributedServiceProvider<T> serviceProvider,
      AccountDeploymentDetails<T> deploymentDetails,
      SpinnakerRuntimeSettings runtimeSettings,
      List<SpinnakerService.Type> serviceTypes) {
    DaemonTaskHandler.newStage("Checking if it is safe to roll back all services");
    for (DistributedService distributedService : serviceProvider.getPrioritizedDistributedServices(serviceTypes)) {
      SpinnakerService service = distributedService.getService();
      ServiceSettings settings = runtimeSettings.getServiceSettings(service);
      boolean safeToUpdate = settings.getSafeToUpdate();

      if (!settings.getEnabled() || distributedService.isRequiredToBootstrap() || !safeToUpdate) {
        continue;
      }

      RunningServiceDetails runningServiceDetails = distributedService.getRunningServiceDetails(deploymentDetails, runtimeSettings);
      if (runningServiceDetails.getInstances().keySet().size() == 1) {
        throw new HalException(Problem.Severity.FATAL, "Service " + service.getCanonicalName() + " has only one server group - there is nothing to rollback to.");
      }
    }

    DaemonTaskHandler.newStage("Rolling back all updatable services");
    for (DistributedService distributedService : serviceProvider.getPrioritizedDistributedServices(serviceTypes)) {
      SpinnakerService service = distributedService.getService();
      ServiceSettings settings = runtimeSettings.getServiceSettings(service);
      if (!settings.getEnabled()) {
        continue;
      }

      boolean safeToUpdate = settings.getSafeToUpdate();
      if (distributedService.isRequiredToBootstrap() || !safeToUpdate) {
        // Do nothing, the bootstrapping services should already be running, and the services that can't be updated
        // having nothing to rollback to
      } else {
        DaemonResponse.StaticRequestBuilder<Void> builder = new DaemonResponse.StaticRequestBuilder<>();
        builder.setBuildResponse(() -> {
          Orca orca = serviceProvider
              .getDeployableService(SpinnakerService.Type.ORCA_BOOTSTRAP, Orca.class)
              .connectToPrimaryService(deploymentDetails, runtimeSettings);
          DaemonTaskHandler.message("Rolling back " + distributedService.getServiceName() + " via Spinnaker red/black");
          rollbackService(deploymentDetails, orca, distributedService, runtimeSettings);

          return null;
        });

        DaemonTaskHandler.submitTask(builder::build, "Rollback " + distributedService.getServiceName());
      }
    }

    DaemonTaskHandler.message("Waiting on rollbacks to complete");
    DaemonTaskHandler.reduceChildren(null, (t1, t2) -> null, (t1, t2) -> null)
        .getProblemSet().throwifSeverityExceeds(Problem.Severity.WARNING);

  }

  @Override
  public void collectLogs(DistributedServiceProvider<T> serviceProvider, AccountDeploymentDetails<T> deploymentDetails, SpinnakerRuntimeSettings runtimeSettings, List<SpinnakerService.Type> serviceTypes) {
    for (DistributedService distributedService : serviceProvider.getPrioritizedDistributedServices(serviceTypes)) {
      if (distributedService instanceof LogCollector) {
        ((LogCollector) distributedService).collectLogs(deploymentDetails, runtimeSettings);
      } else {
        log.warn(distributedService.getServiceName() + " cannot have logs collected");
      }
    }
  }

  @Override
  public RemoteAction connectCommand(DistributedServiceProvider<T> serviceProvider, AccountDeploymentDetails<T> deploymentDetails, SpinnakerRuntimeSettings runtimeSettings, List<SpinnakerService.Type> serviceTypes) {
    RemoteAction result = new RemoteAction();

    String connectCommands = String.join(" &\n", serviceTypes.stream()
        .map(t -> serviceProvider.getDeployableService(t).connectCommand(deploymentDetails, runtimeSettings))
        .collect(Collectors.toList()));
    result.setScript("#!/bin/bash\n" + connectCommands);
    result.setScriptDescription("The generated script will open connections to the API & UI servers using ssh tunnels");
    result.setAutoRun(false);
    return result;
  }

  @Override
  public void flushInfrastructureCaches(DistributedServiceProvider<T> serviceProvider, AccountDeploymentDetails<T> deploymentDetails, SpinnakerRuntimeSettings runtimeSettings) {
    try {
      Jedis jedis = (Jedis) serviceProvider
          .getDeployableService(SpinnakerService.Type.REDIS)
          .connectToPrimaryService(deploymentDetails, runtimeSettings);
      RedisService.flushKeySpace(jedis, "com.netflix.spinnaker.clouddriver*");
    } catch (Exception e) {
      throw new HalException(Problem.Severity.FATAL, "Failed to flush redis cache: " + e.getMessage());
    }
  }

  @Override
  public RemoteAction deploy(DistributedServiceProvider<T> serviceProvider,
      AccountDeploymentDetails<T> deploymentDetails,
      ResolvedConfiguration resolvedConfiguration,
      List<SpinnakerService.Type> serviceTypes) {
    SpinnakerRuntimeSettings runtimeSettings = resolvedConfiguration.getRuntimeSettings();

    DaemonTaskHandler.newStage("Deploying Spinnaker");
    // First deploy all services not owned by Spinnaker
    for (DistributedService distributedService : serviceProvider.getPrioritizedDistributedServices(serviceTypes)) {
      SpinnakerService service = distributedService.getService();
      ServiceSettings settings = resolvedConfiguration.getServiceSettings(service);
      if (!settings.getEnabled()) {
        continue;
      }

      DaemonTaskHandler.newStage("Determining status of " + distributedService.getServiceName());
      boolean safeToUpdate = settings.getSafeToUpdate();
      RunningServiceDetails runningServiceDetails = distributedService.getRunningServiceDetails(deploymentDetails, runtimeSettings);

      if (distributedService.isRequiredToBootstrap() || !safeToUpdate) {
        deployServiceManually(deploymentDetails, resolvedConfiguration, distributedService, safeToUpdate);
      } else {
        DaemonResponse.StaticRequestBuilder<Void> builder = new DaemonResponse.StaticRequestBuilder<>();
        builder.setBuildResponse(() -> {
          if (runningServiceDetails.getLatestEnabledVersion() == null) {
            DaemonTaskHandler.newStage("Deploying " + distributedService.getServiceName() + " via provider API");
            deployServiceManually(deploymentDetails, resolvedConfiguration, distributedService, safeToUpdate);
          } else {
            DaemonTaskHandler.newStage("Deploying " + distributedService.getServiceName() + " via red/black");
            Orca orca = serviceProvider
                .getDeployableService(SpinnakerService.Type.ORCA_BOOTSTRAP, Orca.class)
                .connectToPrimaryService(deploymentDetails, runtimeSettings);
            deployServiceWithOrca(deploymentDetails, resolvedConfiguration, orca, distributedService);
          }

          return null;
        });
        DaemonTaskHandler.submitTask(builder::build, "Deploy " + distributedService.getServiceName());
      }
    }

    DaemonTaskHandler.message("Waiting on deployments to complete");
    DaemonTaskHandler.reduceChildren(null, (t1, t2) -> null, (t1, t2) -> null)
        .getProblemSet().throwifSeverityExceeds(Problem.Severity.WARNING);

    reapOrcaServerGroups(deploymentDetails, runtimeSettings, serviceProvider.getDeployableService(SpinnakerService.Type.ORCA));
    reapRoscoServerGroups(deploymentDetails, runtimeSettings, serviceProvider.getDeployableService(SpinnakerService.Type.ROSCO));

    return new RemoteAction();
  }

  private <T extends Account> void deployServiceManually(AccountDeploymentDetails<T> details,
      ResolvedConfiguration resolvedConfiguration,
      DistributedService distributedService,
      boolean safeToUpdate) {
    DaemonTaskHandler.message("Manually deploying " + distributedService.getServiceName());
    List<ConfigSource> configs = distributedService.stageProfiles(details, resolvedConfiguration);
    distributedService.ensureRunning(details, resolvedConfiguration, configs, safeToUpdate);
  }

  private <T extends Account> void deployServiceWithOrca(AccountDeploymentDetails<T> details,
      ResolvedConfiguration resolvedConfiguration,
      Orca orca,
      DistributedService distributedService) {
    SpinnakerRuntimeSettings runtimeSettings = resolvedConfiguration.getRuntimeSettings();
    ServiceSettings settings = resolvedConfiguration.getServiceSettings(distributedService.getService());
    RunningServiceDetails runningServiceDetails = distributedService.getRunningServiceDetails(details, runtimeSettings);
    Supplier<String> idSupplier;
    if (!runningServiceDetails.getLoadBalancer().isExists()) {
      Map<String, Object> task = distributedService.buildUpsertLoadBalancerTask(details, runtimeSettings);
      idSupplier = () -> orca.submitTask(task).get("ref");
      orcaRunner.monitorTask(idSupplier, orca);
    }

    List<String> configs = distributedService.stageProfiles(details, resolvedConfiguration);
    Integer maxRemaining = MAX_REMAINING_SERVER_GROUPS;
    boolean scaleDown = true;
    if (distributedService.isStateful()) {
      maxRemaining = null;
      scaleDown = false;
    }

    Map<String, Object> pipeline = distributedService.buildDeployServerGroupPipeline(details, runtimeSettings, configs, maxRemaining, scaleDown);
    idSupplier = () -> orca.orchestrate(pipeline).get("ref");
    orcaRunner.monitorPipeline(idSupplier, orca);
  }

  private <T extends Account> void rollbackService(AccountDeploymentDetails<T> details,
      Orca orca,
      DistributedService distributedService,
      SpinnakerRuntimeSettings runtimeSettings) {
    DaemonTaskHandler.newStage("Rolling back " + distributedService.getServiceName());
    Map<String, Object> pipeline = distributedService.buildRollbackPipeline(details, runtimeSettings);
    Supplier<String> idSupplier = () -> orca.orchestrate(pipeline).get("ref");
    orcaRunner.monitorPipeline(idSupplier, orca);
  }

  private <T extends Account> void reapRoscoServerGroups(AccountDeploymentDetails<T> details,
      SpinnakerRuntimeSettings runtimeSettings,
      DistributedService<Rosco, T> roscoService
  ) {
    Rosco rosco =  roscoService.connectToPrimaryService(details, runtimeSettings);
    Rosco.AllStatus allStatus = rosco.getAllStatus();
    ServiceSettings roscoSettings = runtimeSettings.getServiceSettings(roscoService.getService());
    RunningServiceDetails roscoDetails = roscoService.getRunningServiceDetails(details, runtimeSettings);

    Set<String> activeInstances = new HashSet<>();

    allStatus.getInstances().forEach((s, e) -> {
      if (e.getStatus().equals(Rosco.Status.RUNNING)) {
        String[] split = s.split("@");
        if (split.length != 2) {
          log.warn("Unsupported rosco status format");
          return;
        }

        String instanceId = split[1];
        activeInstances.add(instanceId);
      }
    });

    Map<Integer, Integer> executionsByServerGroupVersion = new HashMap<>();

    roscoDetails.getInstances().forEach((s, is) -> {
      int count = is.stream().reduce(0,
          (c, i) -> c + (activeInstances.contains(i) ? 1 : 0),
          (a, b) -> a + b);
      executionsByServerGroupVersion.put(s, count);
    });

    // Omit the last deployed roscos from being deleted, since they are kept around for rollbacks.
    List<Integer> allRoscos = new ArrayList<>(executionsByServerGroupVersion.keySet());
    allRoscos.sort(Integer::compareTo);

    int roscoCount = allRoscos.size();
    if (roscoCount <= MAX_REMAINING_SERVER_GROUPS) {
      return;
    }

    allRoscos = allRoscos.subList(0, roscoCount - MAX_REMAINING_SERVER_GROUPS);
    for (Integer roscoVersion : allRoscos) {
      if (executionsByServerGroupVersion.get(roscoVersion) == 0) {
        DaemonTaskHandler.message("Reaping old rosco server group sequence " + roscoVersion);
        roscoService.deleteVersion(details, roscoSettings, roscoVersion);
      }
    }
  }

  private <T extends Account> void reapOrcaServerGroups(AccountDeploymentDetails<T> details,
      SpinnakerRuntimeSettings runtimeSettings,
      DistributedService<Orca, T> orcaService) {
    Orca orca = orcaService.connectToPrimaryService(details, runtimeSettings);
    Map<String, ActiveExecutions> executions = orca.getActiveExecutions();
    ServiceSettings orcaSettings = runtimeSettings.getServiceSettings(orcaService.getService());
    RunningServiceDetails orcaDetails = orcaService.getRunningServiceDetails(details, runtimeSettings);

    Map<String, Integer> executionsByInstance = new HashMap<>();

    executions.forEach((s, e) -> {
      String instanceId = s.split("@")[1];
      executionsByInstance.put(instanceId, e.getCount());
    });

    Map<Integer, Integer> executionsByServerGroupVersion = new HashMap<>();

    orcaDetails.getInstances().forEach((s, is) -> {
      int count = is.stream().reduce(0,
          (c, i) -> c + executionsByInstance.getOrDefault(i.getId(), 0),
          (a, b) -> a + b);
      executionsByServerGroupVersion.put(s, count);
    });

    // Omit the last deployed orcas from being deleted, since they are kept around for rollbacks.
    List<Integer> allOrcas = new ArrayList<>(executionsByServerGroupVersion.keySet());
    allOrcas.sort(Integer::compareTo);

    int orcaCount = allOrcas.size();
    if (orcaCount <= MAX_REMAINING_SERVER_GROUPS) {
      return;
    }

    allOrcas = allOrcas.subList(0, orcaCount - MAX_REMAINING_SERVER_GROUPS);
    for (Integer orcaVersion : allOrcas) {
      // TODO(lwander) consult clouddriver to ensure this orca isn't enabled
      if (executionsByServerGroupVersion.get(orcaVersion) == 0) {
        DaemonTaskHandler.message("Reaping old orca instance " + orcaVersion);
        orcaService.deleteVersion(details, orcaSettings, orcaVersion);
      }
    }
  }
}
