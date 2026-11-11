package io.github.gumwoo.fulfillment.infra.config;

import io.github.gumwoo.fulfillment.core.port.TransactionRunner;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

public class SpringTransactionRunner implements TransactionRunner {

    private final TransactionTemplate template;

    public SpringTransactionRunner(TransactionTemplate template) {
        this.template = template;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
