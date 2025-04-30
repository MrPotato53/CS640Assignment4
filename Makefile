JAVAC = javac
SRC_DIR = TCPImplementation
JAVA_SOURCES := $(wildcard $(SRC_DIR)/*.java)

.PHONY: all clean run

all: compile

compile:
	$(JAVAC) -d . $(JAVA_SOURCES)

clean:
	@echo "Cleaning up class files…"
	find $(SRC_DIR) -name "*.class" -delete