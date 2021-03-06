/*
 * ToroDB Server
 * Copyright © 2014 8Kdata Technology (www.8kdata.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.torodb.standalone.config.model.protocol.mongo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.torodb.packaging.config.annotation.Description;
import com.torodb.packaging.config.model.protocol.mongo.Net;
import com.torodb.packaging.config.util.ConfigUtils;
import com.torodb.packaging.config.validation.NoDuplicatedReplName;
import com.torodb.packaging.config.validation.RequiredParametersForAuthentication;
import com.torodb.packaging.config.validation.SslEnabledForX509Authentication;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

@Description("config.protocol.mongo")
@JsonPropertyOrder({"net", "replication", "cursorTimeout", "mongopassFile"})
public class Mongo {

  @NotNull
  @Valid
  @JsonProperty(required = true)
  private Net net = new Net();
  @Valid
  @NoDuplicatedReplName
  @SslEnabledForX509Authentication
  @RequiredParametersForAuthentication
  @JsonDeserialize(contentAs = Replication.class)
  private Replication replication;
  @Description("config.mongo.mongopassFile")
  @JsonProperty(required = true)
  private String mongopassFile = ConfigUtils.getUserHomeFilePath(".mongopass");

  public Net getNet() {
    return net;
  }

  public void setNet(Net net) {
    this.net = net;
  }

  public Replication getReplication() {
    return replication;
  }

  public void setReplication(Replication replication) {
    this.replication = replication;
  }

  public String getMongopassFile() {
    return mongopassFile;
  }

  public void setMongopassFile(String mongopassFile) {
    this.mongopassFile = mongopassFile;
  }
}
