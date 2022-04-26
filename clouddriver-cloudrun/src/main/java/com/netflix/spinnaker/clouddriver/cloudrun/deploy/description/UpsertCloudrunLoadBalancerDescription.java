/*
 * Copyright 2022 OpsMx, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

package com.netflix.spinnaker.clouddriver.cloudrun.deploy.description;

import com.netflix.spinnaker.clouddriver.cloudrun.model.CloudrunTrafficSplit;

public class UpsertCloudrunLoadBalancerDescription extends AbstractCloudrunCredentialsDescription {
  private String accountName;
  private String loadBalancerName;
  private String region;
  private CloudrunTrafficSplit split;
  private CloudrunTrafficSplitDescription splitDescription;
  private Boolean migrateTraffic;

  public String getAccountName() {
    return accountName;
  }

  public void setAccountName(String accountName) {
    this.accountName = accountName;
  }

  public String getLoadBalancerName() {
    return loadBalancerName;
  }

  public void setLoadBalancerName(String loadBalancerName) {
    this.loadBalancerName = loadBalancerName;
  }

  public CloudrunTrafficSplit getSplit() {
    return split;
  }

  // public void setSplit(CloudrunTrafficSplit split) {
  //  this.split = split;
  // }

  public CloudrunTrafficSplitDescription getSplitDescription() {
    return splitDescription;
  }

  public void setSplitDescription(CloudrunTrafficSplitDescription splitDescription) {
    this.splitDescription = splitDescription;
  }

  public Boolean getMigrateTraffic() {
    return migrateTraffic;
  }

  public void setMigrateTraffic(Boolean migrateTraffic) {
    this.migrateTraffic = migrateTraffic;
  }

  public String getRegion() {
    return region;
  }

  public void setRegion(String region) {
    this.region = region;
  }
}
