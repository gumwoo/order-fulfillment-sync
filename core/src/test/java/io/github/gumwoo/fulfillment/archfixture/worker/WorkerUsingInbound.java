package io.github.gumwoo.fulfillment.archfixture.worker;

import io.github.gumwoo.fulfillment.archfixture.inbound.InboundHandler;

/** 위반 픽스처: 실행 모듈(worker)이 다른 실행 모듈(inbound)을 참조한다. */
public class WorkerUsingInbound {
    public void run(InboundHandler handler) {
        handler.handle();
    }
}
