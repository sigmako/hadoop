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

package org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.converter.weightconversion;

import java.util.List;

import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CapacitySchedulerConfiguration;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.QueuePath;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.FSParentQueue;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.FSQueue;

public class WeightToWeightConverter
    implements CapacityConverter {
  private static final String ROOT_QUEUE = "root";

  @Override
  public void convertWeightsForChildQueues(FSQueue queue,
      CapacitySchedulerConfiguration csConfig) {
    List<FSQueue> children = queue.getChildQueues();

    if (queue instanceof FSParentQueue || !children.isEmpty()) {
      QueuePath queuePath = new QueuePath(queue.getName());
      if (queue.getName().equals(ROOT_QUEUE)) {
        csConfig.setNonLabeledQueueWeight(queuePath, queue.getWeight());
      }

      children.forEach(fsQueue -> csConfig.setNonLabeledQueueWeight(
          new QueuePath(fsQueue.getName()), fsQueue.getWeight()));
      csConfig.setAutoQueueCreationV2Enabled(queuePath, true);
    }
  }
}
