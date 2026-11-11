package io.github.gumwoo.fulfillment.scenario.support;

import io.github.gumwoo.fulfillment.inbound.InboundApiApplication;

/** 시나리오 테스트가 띄우는 실행 모듈. 기능이 늘면 여기에 추가한다. */
public enum App {
    INBOUND(InboundApiApplication.class, InboundApiApplication.CONFIG_NAME);

    final Class<?> mainClass;
    final String configName;

    App(Class<?> mainClass, String configName) {
        this.mainClass = mainClass;
        this.configName = configName;
    }
}
