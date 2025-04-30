JAVAC = javac
SRC_DIR = src
SRCS    = $(wildcard $(SRC_DIR)/*.java)

.PHONY: all clean

all:
	# compile all .java into .class files side-by-side
	$(JAVAC) -d $(SRC_DIR) $(SRCS)

clean:
	rm -f $(SRC_DIR)/*.class