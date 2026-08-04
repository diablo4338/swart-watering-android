ROOT_DIR := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))

# Android Emulator reaches services on the host through 10.0.2.2.
APK_API_BASE_URL ?= http://10.0.2.2:8081/
# This must match SMART_WATERING_ANDROID_RELEASES_DIR on the backend host.
APK_RELEASES_DIR ?= /srv/smart-watering/releases
# Use the same debug key as Android Studio so downloaded test APKs are upgradable.
APK_DEBUG_KEYSTORE ?= /home/sergei/.android/debug.keystore
APK_VERSION_NAME ?= 1.2.3
APK_VERSION_CODE ?= 101

.PHONY: test-publish

test-publish:
	APK_API_BASE_URL="$(APK_API_BASE_URL)" \
	APK_RELEASES_DIR="$(APK_RELEASES_DIR)" \
	APK_DEBUG_KEYSTORE="$(APK_DEBUG_KEYSTORE)" \
	APK_VERSION_NAME="$(APK_VERSION_NAME)" \
	APK_VERSION_CODE="$(APK_VERSION_CODE)" \
		bash "$(ROOT_DIR)/scripts/publish-test-apk.sh"
