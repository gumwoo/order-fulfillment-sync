package io.github.gumwoo.fulfillment.core.architecture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.gumwoo.fulfillment.archfixture.infra.config.ShopbyClientWiring;
import io.github.gumwoo.fulfillment.archfixture.infra.shopby.FixtureShopbyClientAdapter;
import io.github.gumwoo.fulfillment.archfixture.infra.shopby.ShopbyHttpCaller;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 규칙이 위반을 실제로 잡는지 검사한다. archfixture 패키지에는 일부러 규칙을 어긴 클래스가 있다.
 * 이 테스트가 깨지면 규칙이 약해졌다는 뜻이다.
 */
class ArchitectureRulesViolationTest {

    static final String FIXTURE_ROOT = "io.github.gumwoo.fulfillment.archfixture";

    static JavaClasses fixtureClasses;

    @BeforeAll
    static void importFixtures() {
        fixtureClasses = new ClassFileImporter().importPackages(FIXTURE_ROOT);
    }

    @Test
    void core에서_Spring을_참조하면_실패한다() {
        assertThatThrownBy(() -> ArchitectureRules.coreIsFrameworkFree(FIXTURE_ROOT).check(fixtureClasses))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("SpringDependentDomain")
                .hasMessageContaining("고치는 방법");
    }

    @Test
    void 실행_모듈끼리_참조하면_실패한다() {
        assertThatThrownBy(() -> ArchitectureRules.executableModulesDoNotReferenceEachOther(FIXTURE_ROOT).check(fixtureClasses))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("WorkerUsingInbound")
                .hasMessageContaining("고치는 방법");
    }

    @Test
    void 실행_모듈이_샵바이_어댑터를_직접_쓰면_실패한다() {
        assertThatThrownBy(() -> ArchitectureRules.shopbyAdapterIsReachedOnlyThroughPort(FIXTURE_ROOT).check(fixtureClasses))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ReconcilerBypassingShopbyClient")
                .hasMessageContaining("고치는 방법");
    }

    @Test
    void 샵바이_내부_클래스를_infra의_다른_곳에서_쓰면_실패한다() {
        assertThatThrownBy(() -> ArchitectureRules.shopbyInternalsAreEncapsulated(FIXTURE_ROOT).check(fixtureClasses))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("TplAdapterUsingShopbyHttp")
                .hasMessageContaining("고치는 방법");
    }

    @Test
    void 실행_모듈이_HTTP_클라이언트를_직접_쓰면_실패한다() {
        assertThatThrownBy(() -> ArchitectureRules.httpClientsOnlyInAdapterPackages(FIXTURE_ROOT).check(fixtureClasses))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("AdminCallingHttpDirectly")
                .hasMessageContaining("고치는 방법");
    }

    @Test
    void infra의_범용_헬퍼가_HttpURLConnection을_쓰면_실패한다() {
        assertThatThrownBy(() -> ArchitectureRules.httpClientsOnlyInAdapterPackages(FIXTURE_ROOT).check(fixtureClasses))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("GenericHttpHelper");
    }

    @Test
    void ShopbyClient_구현체가_infra_shopby_밖에_있으면_실패한다() {
        assertThatThrownBy(() -> ArchitectureRules.shopbyClientImplementationsLiveInShopbyAdapter(FIXTURE_ROOT).check(fixtureClasses))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("RogueShopbyClient")
                .hasMessageContaining("고치는 방법")
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(e.getMessage())
                        .doesNotContain("FixtureShopbyClientAdapter"));
    }

    @Test
    void ShopbyClient_구현체는_밖에서_써도_된다() {
        // 오탐 방지: 포트 구현체를 infra.shopby 밖에서 생성·사용하는 것은 허용된다
        JavaClasses allowedOnly = new ClassFileImporter().importClasses(
                ShopbyHttpCaller.class, FixtureShopbyClientAdapter.class, ShopbyClientWiring.class);
        ArchitectureRules.shopbyInternalsAreEncapsulated(FIXTURE_ROOT).check(allowedOnly);
    }
}
