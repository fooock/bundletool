/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.tools.build.bundletool.device;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.android.bundle.Commands.ApkDescription;
import com.android.bundle.Commands.ApkSet;
import com.android.bundle.Commands.BuildApksResult;
import com.android.bundle.Commands.Variant;
import com.android.bundle.Devices.DeviceSpec;
import com.android.bundle.Targeting.ApkTargeting;
import com.android.bundle.Targeting.VariantTargeting;
import com.android.tools.build.bundletool.exceptions.CommandExecutionException;
import com.android.tools.build.bundletool.model.ZipPath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.util.Optional;

/** Calculates whether a given device can be served an APK generated by the bundle tool. */
public class ApkMatcher {

  private final SdkVersionMatcher sdkVersionMatcher;
  private final AbiMatcher abiMatcher;
  private final ScreenDensityMatcher screenDensityMatcher;
  private final LanguageMatcher languageMatcher;
  private final Optional<ImmutableSet<String>> allowedSplitModules;

  public ApkMatcher(DeviceSpec deviceSpec) {
    this(deviceSpec, Optional.empty());
  }

  public ApkMatcher(DeviceSpec deviceSpec, Optional<ImmutableSet<String>> allowedSplitModules) {
    checkArgument(
        !allowedSplitModules.isPresent() || !allowedSplitModules.get().isEmpty(),
        "Set of allowed split modules cannot be empty.");
    this.sdkVersionMatcher = new SdkVersionMatcher(deviceSpec);
    this.abiMatcher = new AbiMatcher(deviceSpec);
    this.screenDensityMatcher = new ScreenDensityMatcher(deviceSpec);
    this.languageMatcher = new LanguageMatcher(deviceSpec);
    this.allowedSplitModules = allowedSplitModules;
  }

  /**
   * Returns all APKs that should be installed on a device.
   *
   * @param buildApksResult describes APKs produced by the BundleTool
   * @return paths of the matching APKs as represented by {@link ApkDescription#getPath()}
   */
  public ImmutableList<ZipPath> getMatchingApks(BuildApksResult buildApksResult) {
    Optional<Variant> matchingVariant = getMatchingVariant(buildApksResult);

    return matchingVariant.isPresent()
        ? getMatchingApksFromVariant(matchingVariant.get())
        : ImmutableList.of();
  }

  private Optional<Variant> getMatchingVariant(BuildApksResult buildApksResult) {
    ImmutableList<Variant> matchingVariants =
        buildApksResult
            .getVariantList()
            .stream()
            .filter(variant -> matchesVariantTargeting(variant.getTargeting()))
            .collect(toImmutableList());

    // It would be a mistake if more than one variant matched.
    return matchingVariants.isEmpty()
        ? Optional.empty()
        : Optional.of(Iterables.getOnlyElement(matchingVariants));
  }

  private ImmutableList<ZipPath> getMatchingApksFromVariant(Variant variant) {
    ImmutableList.Builder<ZipPath> matchedApksBuilder = ImmutableList.builder();

    for (ApkSet apkSet : variant.getApkSetList()) {
      String moduleName = apkSet.getModuleMetadata().getName();

      for (ApkDescription apkDescription : apkSet.getApkDescriptionList()) {
        ApkTargeting apkTargeting = apkDescription.getTargeting();
        boolean isSplitApk = apkDescription.hasSplitApkMetadata();

        if (matchesApk(apkTargeting, isSplitApk, moduleName)) {
          matchedApksBuilder.add(ZipPath.create(apkDescription.getPath()));
        }
      }
    }

    return matchedApksBuilder.build();
  }

  /**
   * Returns whether a given APK generated by the Bundle Tool should be installed on a device.
   *
   * @return whether to deliver the APK to the device
   */
  public boolean matchesApk(ApkTargeting apkTargeting, boolean isSplit, String moduleName) {
    boolean matchesTargeting = matchesApkTargeting(apkTargeting);

    if (isSplit) {
      boolean matchesSplitModuleName =
          !allowedSplitModules.isPresent() || allowedSplitModules.get().contains(moduleName);
      return matchesTargeting && matchesSplitModuleName;
    } else {
      if (matchesTargeting && allowedSplitModules.isPresent()) {
        throw CommandExecutionException.builder()
            .withMessage("Cannot restrict modules when the device matches a non-split APK.")
            .build();
      }
      return matchesTargeting;
    }
  }

  private boolean matchesVariantTargeting(VariantTargeting variantTargeting) {
    return sdkVersionMatcher
        .getVariantTargetingPredicate()
        .and(abiMatcher.getVariantTargetingPredicate())
        .and(screenDensityMatcher.getVariantTargetingPredicate())
        .test(variantTargeting);
  }

  private boolean matchesApkTargeting(ApkTargeting apkTargeting) {
    return sdkVersionMatcher
        .getApkTargetingPredicate()
        .and(abiMatcher.getApkTargetingPredicate())
        .and(screenDensityMatcher.getApkTargetingPredicate())
        .and(languageMatcher.getApkTargetingPredicate())
        .test(apkTargeting);
  }
}
