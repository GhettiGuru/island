# Frequently Asked Questions

- ## Is Island compatible with my device?

  If you are facing setup failure, malfunction with apps in Island or any other issues and wondering whether Island is compatible with your device / ROM, please try [TestDPC](https://play.google.com/store/apps/details?id=com.afwsamples.testdpc)，the official "Android for Work" test tool from Google.

  If TestDPC also does not work properly on your device, there's a high chance that your device / ROM is incompatible with "Android for Work", please contact your device vendor or ROM maintainer, mentioning the TestDPC not working issue.

  If TestDPC works flawlessly but Island does not, please [file an issue](https://github.com/oasisfeng/island/issues) with logcat attached for us to further analyze. If you are not familiar with logcat, please "Take bug report" in system "Settings - (System) - Developer options" and share the bug-report privately.

- ## Error "Island is not ready" when performing some actions

  This is normal if the Island space is paused (deactivated), since some actions require Island space being activated.

  If you encounter this error otherwise and Island app is installed from Google Play Store, you need to install an [Extension Pack](https://github.com/oasisfeng/island/releases/tag/sideplay.v1.0) additionally. After the Extension Pack is installed, reboot your device and try again. **You may need to destroy and re-create the Island space to eliminate this error in some cases.**

- ## Error in setup: "Oops! Couldn't set up your work profile. Contact your IT department or try again later."

  If your device has "Dual Apps", "App Twin" or similar feature, it may be implemented by the same "Android for Work" infrastructure as Island. This leads to a conflict, thus you can not use both Island and these features.

  Otherwise, your device is probably incompatible with Android for Work. At present, you can only [setup Island manually](/setup.md).

- ## Error in setup: "Custom OS installed" (Samsung-specific)

  Samsung enforces extra limitation beyond stock Android, forbidding work profile to be created with custom recovery installed. You can either temporarily revert back to stock recovery and flash custom recovery again after setup, or [setup Island manually](/setup.md).

- ## Why can't I clone apps not installed by Google Play Store?

  Due to distribution policy enforced by Google Play Store, the version of Island released on Google Play Store is not allowed to use an essential permission (REQUEST_INSTALL_PACKAGES), thus cannot clone apps by installing them into Island. For apps installed by Google Play Store, Island will launch Google Play Store inside Island to do the clone (it's almost instant).

  If you need to clone apps not installed by Google Play Store, you can either grant Root / Shizuku permission to Island, or install the [Extension Pack](https://github.com/oasisfeng/island/releases/tag/sideplay.v1.0) additionally. (It must be installed in both Mainland and Island)
