KOTLINC ?= kotlinc
JAVA ?= java
BUILD_DIR := build
MAIN_SOURCES := $(shell find src/main/kotlin -name '*.kt' -print)
TEST_SOURCES := $(shell find src/test/kotlin -name '*.kt' -print)

.PHONY: build test clean

build:
	mkdir -p $(BUILD_DIR)
	$(KOTLINC) $(MAIN_SOURCES) -include-runtime -d $(BUILD_DIR)/cplus.jar

test:
	mkdir -p $(BUILD_DIR)
	$(KOTLINC) $(MAIN_SOURCES) $(TEST_SOURCES) -include-runtime -d $(BUILD_DIR)/cplus-tests.jar
	$(JAVA) -cp $(BUILD_DIR)/cplus-tests.jar cplus.TranspilerTestKt

clean:
	rm -rf $(BUILD_DIR)
