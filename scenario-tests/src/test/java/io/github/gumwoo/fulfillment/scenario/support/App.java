package io.github.gumwoo.fulfillment.scenario.support;

import io.github.gumwoo.fulfillment.admin.AdminApiApplication;
import io.github.gumwoo.fulfillment.inbound.InboundApiApplication;
import io.github.gumwoo.fulfillment.mock.MockServersApplication;
import io.github.gumwoo.fulfillment.reconciler.ReconcilerApplication;
import io.github.gumwoo.fulfillment.worker.WorkerApplication;

/** 시나리오 테스트가 띄우는 실행 모듈. 선언 순서대로 켠다 (mock이 먼저). */
public enum App {
    MOCK(MockServersApplication.class, MockServersApplication.CONFIG_NAME),
    INBOUND(InboundApiApplication.class, InboundApiApplication.CONFIG_NAME),
    WORKER(WorkerApplication.class, WorkerApplication.CONFIG_NAME),
    /** 여러 인스턴스 흉내 (F-07) */
    WORKER_2(WorkerApplication.class, WorkerApplication.CONFIG_NAME),
    WORKER_3(WorkerApplication.class, WorkerApplication.CONFIG_NAME),
    RECONCILER(ReconcilerApplication.class, ReconcilerApplication.CONFIG_NAME),
    ADMIN(AdminApiApplication.class, AdminApiApplication.CONFIG_NAME);

    final Class<?> mainClass;
    final String configName;

    App(Class<?> mainClass, String configName) {
        this.mainClass = mainClass;
        this.configName = configName;
    }
}
