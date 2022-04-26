/*
 * Copyright 2022 OpsMx, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.cloudrun.deploy.description;

import com.netflix.spinnaker.clouddriver.deploy.description.EnableDisableDescriptionTrait;
import java.util.Collection;

public class EnableDisableCloudrunDescription extends AbstractCloudrunCredentialsDescription
    implements EnableDisableDescriptionTrait {

  String accountName;
  String serverGroupName;

  Boolean migrateTraffic;

  /** @return */
  @Override
  public Integer getDesiredPercentage() {
    return null;
  }

  /** @param */
  @Override
  public void setDesiredPercentage(Integer percentage) {}

  @Override
  public Collection<String> getServerGroupNames() {
    return null;
  }

  @Override
  public String getServerGroupName() {
    return this.serverGroupName;
  }

  @Override
  public void setServerGroupName(String serverGroupName) {
    this.serverGroupName = serverGroupName;
  }
}
