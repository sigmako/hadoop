/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity;

import org.apache.hadoop.yarn.api.protocolrecords.SubmitApplicationRequest;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.server.resourcemanager.MockRM;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppState;
import org.apache.hadoop.yarn.util.resource.Resources;
import org.junit.Test;

import java.io.IOException;

public class TestCapacitySchedulerAmbiguousLeafs {
  /**
   * Internal counter for incremental application id generation
   */
  int appId = 0;

  private static final QueuePath ROOT = new QueuePath(CapacitySchedulerConfiguration.ROOT);
  private static final QueuePath DEFAULT = new QueuePath(CapacitySchedulerConfiguration.ROOT +
      ".default");
  private static final QueuePath A = new QueuePath(CapacitySchedulerConfiguration.ROOT +
      ".a");
  private static final QueuePath B = new QueuePath(CapacitySchedulerConfiguration.ROOT +
      ".b");
  private static final QueuePath A_UNIQUE = new QueuePath(CapacitySchedulerConfiguration.ROOT +
      ".a.unique");
  private static final QueuePath A_AMBI = new QueuePath(CapacitySchedulerConfiguration.ROOT +
      ".a.ambi");
  private static final QueuePath B_AMBI = new QueuePath(CapacitySchedulerConfiguration.ROOT +
      ".b.ambi");

  /**
   * Helper method to submit applications via RMClientService, to make sure
   * all submissions go through RMAppManager.
   * @param rm The resource manager instance
   * @param queue Name of the queue to submit the application to
   * @return ApplicationID of the submitted application
   * @throws IOException
   * @throws YarnException
   */
  private ApplicationId submitApplication(MockRM rm, String queue)
      throws IOException, YarnException {
    //Generating incremental application id
    final ApplicationAttemptId appAttemptId = TestUtils
        .getMockApplicationAttemptId(appId++, 1);

    Resource resource = Resources.createResource(1024);
    ContainerLaunchContext amContainerSpec = ContainerLaunchContext
        .newInstance(null, null, null, null, null, null);
    ApplicationSubmissionContext asc = ApplicationSubmissionContext
        .newInstance(appAttemptId.getApplicationId(), "Test application",
            queue, null, amContainerSpec, false, true, 1, resource,
            "applicationType");

    SubmitApplicationRequest req = SubmitApplicationRequest.newInstance(asc);
    rm.getClientRMService().submitApplication(req);
    return appAttemptId.getApplicationId();
  }

  @Test
  public void testAmbiguousSubmissionWithACL() throws Exception {
    YarnConfiguration conf = new YarnConfiguration();
    conf.set(YarnConfiguration.RM_SCHEDULER, CapacityScheduler.class.getName());
    conf.setBoolean(YarnConfiguration.YARN_ACL_ENABLE, true);

    MockRM rm = new MockRM(conf);
    CapacityScheduler cs = (CapacityScheduler)rm.getResourceScheduler();
    CapacitySchedulerConfiguration schedulerConf = cs.getConfiguration();

    schedulerConf.setQueues(ROOT, new String[] {"a", "b", "default"});
    schedulerConf.setAcl(ROOT, QueueACL.SUBMIT_APPLICATIONS, " ");
    schedulerConf.setAcl(ROOT, QueueACL.ADMINISTER_QUEUE, "forbidden forbidden");

    schedulerConf.setQueues(A, new String[] {"unique", "ambi"});
    schedulerConf.setAcl(A, QueueACL.SUBMIT_APPLICATIONS, "forbidden forbidden");
    schedulerConf.setCapacity(A, 45);

    schedulerConf.setQueues(B, new String[] {"ambi"});
    schedulerConf.setCapacity(B, 45);
    schedulerConf.setCapacity(DEFAULT, 10);

    schedulerConf.setCapacity(A_UNIQUE, 50);
    schedulerConf.setAcl(A_UNIQUE, QueueACL.SUBMIT_APPLICATIONS, "* *");
    schedulerConf.setCapacity(A_AMBI, 50);
    schedulerConf.setAcl(A_AMBI, QueueACL.SUBMIT_APPLICATIONS, "* *");
    schedulerConf.setCapacity(B_AMBI, 100);

    schedulerConf.set(CapacitySchedulerConfiguration.MAPPING_RULE_FORMAT, "json");
    //Simple %specified mapping rule for all submissions with skip fallback
    //The %specified needed rule to make sure we get an
    //ApplicationPlacementContext which is required for validating YARN-10787
    schedulerConf.set(CapacitySchedulerConfiguration.MAPPING_RULE_JSON,
        "{\"rules\" : [{\"type\": \"user\", \"policy\" : \"specified\", " +
        "\"fallbackResult\" : \"skip\", \"matches\" : \"*\"}]}");
    schedulerConf.setOverrideWithQueueMappings(true);

    rm.start();
    cs.reinitialize(schedulerConf, rm.getRMContext());


    ApplicationId id = submitApplication(rm, "root.a.unique");
    rm.waitForState(id, RMAppState.ACCEPTED);

    id = submitApplication(rm, "unique");
    rm.waitForState(id, RMAppState.ACCEPTED);

    id = submitApplication(rm, "ambi");
    rm.waitForState(id, RMAppState.FAILED);

    id = submitApplication(rm, "root.a.ambi");
    rm.waitForState(id, RMAppState.ACCEPTED);

    rm.stop();
  }
}
