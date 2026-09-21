VERSION ?= 0.1.0
DIST := dist
ARCHIVE := $(DIST)/vim-cplus-$(VERSION).tar.gz
ZIP := $(DIST)/vim-cplus-$(VERSION).zip

.PHONY: package

package:
	mkdir -p $(DIST)
	tar -czf $(ARCHIVE) autoload ftdetect ftplugin syntax README.md
	zip -qr $(ZIP) autoload ftdetect ftplugin syntax README.md
