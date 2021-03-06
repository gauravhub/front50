/*
 * Copyright 2019 Netflix, Inc.
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
package com.netflix.spinnaker.front50.model.plugins;

import com.netflix.spinnaker.front50.config.annotations.ConditionalOnAnyProviderExceptRedisIsEnabled;
import com.netflix.spinnaker.front50.exception.NotFoundException;
import com.netflix.spinnaker.front50.plugins.PluginBinaryStorageService;
import com.netflix.spinnaker.front50.validator.GenericValidationErrors;
import com.netflix.spinnaker.front50.validator.PluginInfoValidator;
import com.netflix.spinnaker.kork.exceptions.UserException;
import com.netflix.spinnaker.kork.web.exceptions.InvalidRequestException;
import com.netflix.spinnaker.security.AuthenticatedRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;

@Component
@ConditionalOnAnyProviderExceptRedisIsEnabled
public class PluginInfoService {

  private final PluginInfoRepository repository;
  private final Optional<PluginBinaryStorageService> storageService;
  private final List<PluginInfoValidator> validators;

  public PluginInfoService(
      PluginInfoRepository repository,
      Optional<PluginBinaryStorageService> storageService,
      List<PluginInfoValidator> validators) {
    this.repository = repository;
    this.storageService = storageService;
    this.validators = validators;
  }

  public Collection<PluginInfo> findAll() {
    return repository.all();
  }

  public Collection<PluginInfo> findAllByService(@Nonnull String service) {
    return repository.getByService(service);
  }

  public PluginInfo findById(@Nonnull String pluginId) {
    return repository.findById(pluginId);
  }

  public PluginInfo upsert(@Nonnull PluginInfo pluginInfo) {
    validate(pluginInfo);

    try {
      PluginInfo currentPluginInfo = repository.findById(pluginInfo.getId());
      List<PluginInfo.Release> newReleases = new ArrayList<>(pluginInfo.getReleases());

      // upsert plugin info is not an authorized endpoint, so preferred release must be false.
      newReleases.forEach(it -> it.setPreferred(false));

      List<PluginInfo.Release> oldReleases = new ArrayList<>(currentPluginInfo.getReleases());
      newReleases.forEach(
          release -> { // Raise an exception if old releases are being updated.
            if (oldReleases.stream()
                .anyMatch(oldRelease -> oldRelease.getVersion().equals(release.getVersion()))) {
              throw new InvalidRequestException(
                  "Cannot update an existing release: " + release.getVersion());
            }
          });

      List<PluginInfo.Release> allReleases = new ArrayList<>();
      Stream.of(oldReleases, newReleases).forEach(allReleases::addAll);
      pluginInfo.setReleases(allReleases);

      repository.update(pluginInfo.getId(), pluginInfo);
      return pluginInfo;
    } catch (NotFoundException e) {
      return repository.create(pluginInfo.getId(), pluginInfo);
    }
  }

  public void delete(@Nonnull String id) {
    PluginInfo pluginInfo;
    try {
      pluginInfo = findById(id);
    } catch (NotFoundException e) {
      // Do nothing.
      return;
    }

    // Delete each release individually, so that the release binaries are also cleaned up.
    pluginInfo.getReleases().forEach(r -> deleteRelease(id, r.getVersion()));

    repository.delete(id);
  }

  public PluginInfo createRelease(@Nonnull String id, @Nonnull PluginInfo.Release release) {
    release.setLastModifiedBy(AuthenticatedRequest.getSpinnakerUser().orElse("anonymous"));
    release.setLastModified(Instant.now());

    PluginInfo pluginInfo = repository.findById(id);
    pluginInfo.getReleases().add(release);
    cleanupPreferredReleases(pluginInfo, release);

    validate(pluginInfo);
    repository.update(pluginInfo.getId(), pluginInfo);
    return pluginInfo;
  }

  public PluginInfo upsertRelease(@Nonnull String id, @Nonnull PluginInfo.Release release) {
    release.setLastModifiedBy(AuthenticatedRequest.getSpinnakerUser().orElse("anonymous"));
    release.setLastModified(Instant.now());
    PluginInfo pluginInfo = repository.findById(id);
    Optional<PluginInfo.Release> existingRelease =
        pluginInfo.getReleaseByVersion(release.getVersion());

    return existingRelease
        .map(
            r -> {
              pluginInfo.getReleases().remove(r);
              pluginInfo.getReleases().add(release);
              cleanupPreferredReleases(pluginInfo, release);
              validate(pluginInfo);
              repository.update(pluginInfo.getId(), pluginInfo);
              return pluginInfo;
            })
        .orElseThrow(
            () ->
                new NotFoundException(
                    String.format(
                        "Plugin %s with release %s version not found. ",
                        id, release.getVersion())));
  }

  public PluginInfo deleteRelease(@Nonnull String id, @Nonnull String releaseVersion) {
    PluginInfo pluginInfo = repository.findById(id);

    new ArrayList<>(pluginInfo.getReleases())
        .forEach(
            release -> {
              if (release.getVersion().equals(releaseVersion)) {
                pluginInfo.getReleases().remove(release);
              }
            });
    repository.update(pluginInfo.getId(), pluginInfo);
    storageService.ifPresent(it -> it.delete(it.getKey(id, releaseVersion)));
    return pluginInfo;
  }

  /** Set the preferred release. If preferred is true, sets previous preferred release to false. */
  public PluginInfo.Release preferReleaseVersion(
      @Nonnull String id, @Nonnull String releaseVersion, boolean preferred) {
    PluginInfo pluginInfo = repository.findById(id);
    Optional<PluginInfo.Release> release = pluginInfo.getReleaseByVersion(releaseVersion);

    Instant now = Instant.now();
    String user = AuthenticatedRequest.getSpinnakerUser().orElse("anonymous");

    return release
        .map(
            r -> {
              r.setPreferred(preferred);
              r.setLastModified(now);
              r.setLastModifiedBy(user);

              pluginInfo.setReleaseByVersion(releaseVersion, r);
              cleanupPreferredReleases(pluginInfo, r);

              repository.update(pluginInfo.getId(), pluginInfo);
              return r;
            })
        .orElse(null);
  }

  private void validate(PluginInfo pluginInfo) {
    Errors errors = new GenericValidationErrors(pluginInfo);
    validators.forEach(v -> v.validate(pluginInfo, errors));
    if (errors.hasErrors()) {
      throw new ValidationException(errors);
    }
  }

  private void cleanupPreferredReleases(PluginInfo pluginInfo, PluginInfo.Release release) {
    if (release.isPreferred()) {
      Instant now = Instant.now();
      String user = AuthenticatedRequest.getSpinnakerUser().orElse("anonymous");

      pluginInfo.getReleases().stream()
          .filter(it -> !it.getVersion().equals(release.getVersion()))
          .forEach(
              it -> {
                it.setPreferred(false);
                it.setLastModified(now);
                it.setLastModifiedBy(user);
              });
    }
  }

  public static class ValidationException extends UserException {
    Errors errors;

    ValidationException(Errors errors) {
      this.errors = errors;
    }
  }
}
