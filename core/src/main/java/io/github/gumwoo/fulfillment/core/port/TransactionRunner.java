package io.github.gumwoo.fulfillment.core.port;

import java.util.function.Supplier;

/** 트랜잭션 경계 포트. core는 Spring을 모르므로 infra가 구현한다. */
public interface TransactionRunner {

    <T> T inTransaction(Supplier<T> work);
}
