/**
 *  Licensed to the Apache Software Foundation (ASF) under one
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

package org.apache.hadoop.yarn.server.globalpolicygenerator;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.service.Service;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.test.LambdaTestUtils;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertTrue;

/**
 * Unit test for GlobalPolicyGenerator.
 */
public class TestGlobalPolicyGenerator {

  @Test(timeout = 1000)
  public void testNonFederation() {
    Configuration conf = new YarnConfiguration();
    conf.setBoolean(YarnConfiguration.FEDERATION_ENABLED, false);

    // If GPG starts running, this call will not return
    GlobalPolicyGenerator.startGPG(new String[0], conf);
  }

  @Test
  public void testGpgWithFederation() throws InterruptedException, TimeoutException {
    // In this test case, we hope that gpg can start normally in federation mode.
    Configuration conf = new YarnConfiguration();
    conf.setBoolean(YarnConfiguration.FEDERATION_ENABLED, true);

    GlobalPolicyGenerator gpg = new GlobalPolicyGenerator();
    gpg.initAndStart(conf, false);

    GenericTestUtils.waitFor(() -> {
      List<Service> services = gpg.getServices();
      return (services.size() == 1 && gpg.getWebApp() != null);
    }, 100, 5000);
  }

  @Test
  public void testGPGCLI() {
    ByteArrayOutputStream dataOut = new ByteArrayOutputStream();
    ByteArrayOutputStream dataErr = new ByteArrayOutputStream();
    System.setOut(new PrintStream(dataOut));
    System.setErr(new PrintStream(dataErr));
    GlobalPolicyGenerator.main(new String[]{"-help", "-format-policy-store"});
    assertTrue(dataErr.toString().contains(
        "Usage: yarn gpg [-format-policy-store]"));
  }

  @Test
  public void testUserProvidedUGIConf() throws Exception {
    String errMsg = "Invalid attribute value for " +
        CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTHENTICATION + " of DUMMYAUTH";
    Configuration dummyConf = new YarnConfiguration();
    dummyConf.set(CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTHENTICATION, "DUMMYAUTH");
    GlobalPolicyGenerator gpg = new GlobalPolicyGenerator();
    LambdaTestUtils.intercept(IllegalArgumentException.class, errMsg, () -> gpg.init(dummyConf));
    gpg.stop();
  }
}
