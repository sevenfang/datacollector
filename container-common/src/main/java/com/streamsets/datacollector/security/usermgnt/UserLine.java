/**
 * Copyright 2020 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.datacollector.security.usermgnt;

import com.google.common.base.Splitter;
import com.streamsets.pipeline.api.impl.Utils;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public abstract class UserLine extends Line {

  public interface Hasher {
    String hash(String user, String password);
  }

  protected static String getUserLineRegex(String mode) {
    Utils.checkNotNull(mode, "mode");
    return "^\\s*([\\w@.]*):\\s*" + mode + ":([\\w\\-:]*),user(\\s*$|,.*$)";
  }

  private final String mode;
  private final Hasher hasher;
  private String user;
  private String hash;
  private List<String> roles;

  protected UserLine(String mode, Hasher hasher, String value) {
    super(Type.USER, null);
    this.mode = Utils.checkNotNull(mode, "mode");
    this.hasher = Utils.checkNotNull(hasher, "hasher");
    parse(Utils.checkNotNull(value, "value"));
  }

  protected UserLine(String mode, Hasher hasher, String user, String password, List<String> roles) {
    super(Type.USER, null);
    this.mode = Utils.checkNotNull(mode, "mode");
    this.hasher = Utils.checkNotNull(hasher, "hasher");
    this.user = Utils.checkNotNull(user, "user");
    this.hash = hasher.hash(user, Utils.checkNotNull(password, "password"));
    this.roles = Utils.checkNotNull(roles, "roles");
  }

  public String getMode() {
    return mode;
  }

  protected Hasher getHasher() {
    return hasher;
  }

  protected abstract Pattern getPattern();

  private void parse(String value) {
    Matcher matcher = getPattern().matcher(value);
    if (matcher.matches()) {
      user = matcher.group(1);
      hash = matcher.group(2);
      roles = Splitter.on(",").omitEmptyStrings().trimResults().splitToList(matcher.group(3));
    }
  }

  public String getUser() {
    return user;
  }

  public String getHash() {
    return hash;
  }

  public UserLine setPassword(String oldPassword, String newPassword) {
    Utils.checkNotNull(oldPassword, "oldPassword");
    Utils.checkNotNull(newPassword, "newPassword");
    String oldHash = hasher.hash(getUser(), oldPassword);
    if (oldHash.equals(hash)) {
      hash = hasher.hash(getUser(), newPassword);
    } else {
      throw new IllegalArgumentException("Invalid old password");
    }
    return this;
  }

  public boolean verifyPassword(String password) {
    Utils.checkNotNull(password, "password");
    String computedHash = hasher.hash(getUser(), password);
    return computedHash.equals(hash);
  }

  public String resetPassword(long validForMillis) {
    String resetValue = UUID.randomUUID().toString() + ":" + (System.currentTimeMillis() + validForMillis);
    this.hash = getUser() + "_reset_" + hasher.hash(getUser(), resetValue);
    return resetValue;
  }

  public void setPasswordFromReset(String resetValue, String newPassword) {
    int expirationIdx = resetValue.lastIndexOf(":");
    if (expirationIdx == -1) {
      throw new IllegalArgumentException("Invalid reset value");
    }
    long expiration = Long.parseLong(resetValue.substring(expirationIdx + 1));
    if (expiration < System.currentTimeMillis()) {
      throw new IllegalArgumentException("Password reset expired");
    }
    String computedHash = getUser() + "_reset_" + hasher.hash(getUser(), resetValue);
    if (!computedHash.equals(hash)) {
      throw new IllegalArgumentException("Invalid reset value");
    }
    this.hash = hasher.hash(getUser(), newPassword);
  }

  public List<String> getRoles() {
    return roles;
  }

  public UserLine setRoles(List<String> roles) {
    Utils.checkNotNull(roles, "roles");
    this.roles = roles;
    return this;
  }

  @Override
  public String getId() {
    return getUser();
  }

  @Override
  public String getValue() {
    return String.format("%s: %s:%s,user,%s", getUser(), getMode(), getHash(), roles.stream().collect(Collectors.joining(",")));
  }

}
