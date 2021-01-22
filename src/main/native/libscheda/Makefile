
UNAME  := $(shell uname)

CC       = g++
SHARED   = -shared
CFLAGS   = -Wall -O3 -pipe -D_REENTRANT
WINSOCK  = ws2_32
EXTRA_LDFLAGS  =
EXTRA_CPPFLAGS =
LDFLAGS  = $(EXTRA_LDFLAGS)
CPPFLAGS = $(EXTRA_CPPFLAGS)
LIBNAME  = libscheda
LIBEXTENSION = so

JAVA_INCDIR      = $(JDK_HOME)/include
JAVA_INCDIR_PLAF = $(dir $(wildcard $(JAVA_INCDIR)/*/jni_md.h))
CPPFLAGS += -I$(JAVA_INCDIR) -I$(JAVA_INCDIR_PLAF)
CFLAGS += -pthread -fPIC -DPIC

SRC := $(shell find . -name "*.c" -print)
OBJ := $(SRC:%.c=%.o)

CLEAN_EXTENSIONS = o $(LIBEXTENSION)

LIBSCHEDA = $(LIBNAME).$(LIBEXTENSION)

all: $(LIBSCHEDA)

%.o: %.c
	$(CC) $(CFLAGS) $(CPPFLAGS) -c $< -o $@

$(LIBSCHEDA): $(OBJ)
	$(CC) $(SHARED) -o $@ $^ $(LDFLAGS)

clean:
	for extension in $(CLEAN_EXTENSIONS); do \
		find . -name "*.$$extension" | xargs rm -f ; \
	done
	find . -name "*~" | xargs rm -f
