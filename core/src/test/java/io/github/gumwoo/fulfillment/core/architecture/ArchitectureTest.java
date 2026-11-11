package io.github.gumwoo.fulfillment.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** F-14: 실제 운영 코드(테스트 클래스 제외)에 의존 규칙을 적용한다. */
class ArchitectureTest {

    static final String ROOT = "io.github.gumwoo.fulfillment";

    static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"core", "inbound", "worker", "reconciler", "admin"})
    void 검사_대상_모듈의_클래스가_실제로_임포트된다(String module) {
        // 모듈 패키지 이름이 바뀌어 규칙이 빈 집합을 검사하는 일을 막는다
        String modulePackage = ROOT + "." + module;
        assertThat(productionClasses.stream().anyMatch(c -> c.getPackageName().startsWith(modulePackage)))
                .as("%s 패키지에 클래스가 없다. 패키지 이름을 바꿨다면 ArchitectureRules도 함께 고친다", modulePackage)
                .isTrue();
    }

    @Test
    void 픽스처는_운영_코드_검사에_섞이지_않는다() {
        assertThat(productionClasses.stream().noneMatch(c -> c.getPackageName().startsWith(ROOT + ".archfixture")))
                .as("archfixture는 테스트 소스에만 있어야 한다")
                .isTrue();
    }

    @Test
    void core는_Spring_JPA_AWS를_참조하지_않는다() {
        ArchitectureRules.coreIsFrameworkFree(ROOT).check(productionClasses);
    }

    @Test
    void 실행_모듈끼리_참조하지_않는다() {
        ArchitectureRules.executableModulesDoNotReferenceEachOther(ROOT).check(productionClasses);
    }

    @Test
    void 샵바이_어댑터는_포트를_통해서만_쓴다() {
        ArchitectureRules.shopbyAdapterIsReachedOnlyThroughPort(ROOT).check(productionClasses);
    }

    @Test
    void 샵바이_내부_클래스는_밖에서_접근하지_않는다() {
        ArchitectureRules.shopbyInternalsAreEncapsulated(ROOT).check(productionClasses);
    }

    @Test
    void ShopbyClient_구현체는_infra_shopby에만_있다() {
        ArchitectureRules.shopbyClientImplementationsLiveInShopbyAdapter(ROOT).check(productionClasses);
    }

    @Test
    void HTTP_클라이언트는_어댑터_패키지에서만_쓴다() {
        ArchitectureRules.httpClientsOnlyInAdapterPackages(ROOT).check(productionClasses);
    }

    /** Gradle 모듈 디렉터리 → 그 모듈 클래스가 있어야 할 패키지 */
    static final Map<String, String> MODULE_PACKAGES = Map.of(
            "core", "core",
            "infra", "infra",
            "inbound-api", "inbound",
            "worker", "worker",
            "reconciler", "reconciler",
            "admin-api", "admin");

    @Test
    void 모듈의_모든_클래스가_자기_모듈_패키지에_있다() {
        // 규칙은 패키지 이름으로 모듈을 판별한다. 모듈 안에 다른 패키지를 만들면 모든 규칙을 빠져나가므로 막는다
        List<String> misplaced = new ArrayList<>();
        int checked = 0;
        for (JavaClass javaClass : productionClasses) {
            String location = javaClass.getSource().map(s -> s.getUri().toString()).orElse("");
            for (Map.Entry<String, String> module : MODULE_PACKAGES.entrySet()) {
                if (!location.contains("/" + module.getKey() + "/build/")) {
                    continue;
                }
                checked++;
                String expected = ROOT + "." + module.getValue();
                String pkg = javaClass.getPackageName();
                if (!(pkg.equals(expected) || pkg.startsWith(expected + "."))) {
                    misplaced.add(javaClass.getName() + " (" + module.getKey() + " 모듈, 기대 패키지 " + expected + ")");
                }
            }
        }
        assertThat(checked).as("모듈 위치로 판별한 클래스가 없다. 빌드 출력 경로가 바뀌었는지 확인한다").isPositive();
        assertThat(misplaced)
                .as("고치는 방법: 클래스를 자기 모듈 패키지 아래로 옮긴다. 새 모듈이면 MODULE_PACKAGES와 ArchitectureRules에 추가한다")
                .isEmpty();
    }
}
