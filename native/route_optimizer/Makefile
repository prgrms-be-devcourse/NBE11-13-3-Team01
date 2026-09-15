# 네이티브 경로 최적화 빌드.
#
#   make            공유 라이브러리
#   make test       완전탐색 대조 테스트
#   make sanitize   ASan/UBSan 으로 같은 테스트
#   make clean
#
# Gradle 에서도 이 Makefile 을 호출한다. 직접 돌려도 동일하게 동작한다.

CC      ?= cc
CFLAGS  ?= -std=c11 -O2 -Wall -Wextra -Wpedantic -Wconversion -Wshadow
BUILD   ?= build

UNAME_S := $(shell uname -s)
ifeq ($(UNAME_S),Darwin)
  LIB_NAME := libroute_optimizer.dylib
  SHARED   := -dynamiclib
else
  LIB_NAME := libroute_optimizer.so
  SHARED   := -shared
endif

.PHONY: all test sanitize clean print-lib

all: $(BUILD)/$(LIB_NAME)

$(BUILD):
	mkdir -p $(BUILD)

$(BUILD)/$(LIB_NAME): route_optimizer.c route_optimizer.h | $(BUILD)
	$(CC) $(CFLAGS) -fPIC $(SHARED) -o $@ route_optimizer.c

$(BUILD)/test_route_optimizer: route_optimizer.c route_optimizer.h test_route_optimizer.c | $(BUILD)
	$(CC) $(CFLAGS) -o $@ route_optimizer.c test_route_optimizer.c

test: $(BUILD)/test_route_optimizer
	$(BUILD)/test_route_optimizer

# 메모리 오류와 정의되지 않은 동작은 최적화 빌드에서 조용히 지나간다.
# 같은 테스트를 sanitizer 로 한 번 더 돌린다.
sanitize: route_optimizer.c route_optimizer.h test_route_optimizer.c | $(BUILD)
	$(CC) -std=c11 -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer \
	  -o $(BUILD)/test_route_optimizer_san route_optimizer.c test_route_optimizer.c
	$(BUILD)/test_route_optimizer_san

print-lib:
	@echo $(LIB_NAME)

clean:
	rm -rf $(BUILD)
