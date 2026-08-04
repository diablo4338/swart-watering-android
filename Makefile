VERSION_NAME ?=1.2.3
VERSION_CODE ?=10
APK_OUTPUT_DIR ?= build/local-apk
LOCAL_RELEASES_DIR ?= ../docker/releases

ifeq ($(OS),Windows_NT)
APK_GRADLE_BUILD = powershell.exe -NoProfile -Command \
	"[Environment]::SetEnvironmentVariable('VERSION_NAME','$(VERSION_NAME)','Process'); [Environment]::SetEnvironmentVariable('VERSION_CODE','$(VERSION_CODE)','Process'); .\gradlew.bat --no-daemon --console=plain :app:assembleDebug; exit $$LASTEXITCODE"
else
APK_GRADLE_BUILD = VERSION_NAME="$(VERSION_NAME)" VERSION_CODE="$(VERSION_CODE)" ./gradlew --no-daemon --console=plain :app:assembleDebug
endif

.PHONY: apk-local-build

apk-local-build:
	@test -n "$(VERSION_NAME)" || (echo "VERSION_NAME is required (example: 1.2.3-test)" >&2; exit 2)
	@test -n "$(VERSION_CODE)" || (echo "VERSION_CODE is required (example: 1203)" >&2; exit 2)
	@case "$(VERSION_NAME)" in *[!0-9A-Za-z.+-]*) echo "VERSION_NAME may contain only letters, digits, dots, pluses, and hyphens" >&2; exit 2;; esac
	@case "$(VERSION_CODE)" in *[!0-9]*|'') echo "VERSION_CODE must be a positive integer" >&2; exit 2;; esac
	$(APK_GRADLE_BUILD)
	mkdir -p "$(APK_OUTPUT_DIR)"
	cp app/build/outputs/apk/debug/app-debug.apk "$(APK_OUTPUT_DIR)/smart-watering-$(VERSION_NAME)-$(VERSION_CODE)-debug.apk"
	python scripts/publish-local-release.py --apk "$(APK_OUTPUT_DIR)/smart-watering-$(VERSION_NAME)-$(VERSION_CODE)-debug.apk" --releases-dir "$(LOCAL_RELEASES_DIR)" --version-name "$(VERSION_NAME)" --version-code "$(VERSION_CODE)"
	@echo "APK: $(APK_OUTPUT_DIR)/smart-watering-$(VERSION_NAME)-$(VERSION_CODE)-debug.apk"
	@echo "Local release: $(LOCAL_RELEASES_DIR)/latest.json"
