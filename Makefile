JC = javac
JVM = java
SRCDIR = TCP
BINDIR = TCP

.SUFFIXES: .java .class

$(BINDIR)/%.class: $(SRCDIR)/%.java
	$(JC) -d $(BINDIR) $<

CLASSES = $(addprefix $(BINDIR)/, \
	TCPend.class \
	TCPPacket.class \
	TCPSender.class \
	TCPReceiver.class)

default: $(CLASSES)

clean:
	rm -f $(CLASSES)

.PHONY: default clean 