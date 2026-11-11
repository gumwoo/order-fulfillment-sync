package io.github.gumwoo.fulfillment.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * ARCHITECTURE.md의 의존 규칙. 루트 패키지를 인자로 받아 실제 코드와 위반 픽스처에 같은 규칙을 적용한다.
 * 실패 메시지(because)에 고치는 방법을 적는다.
 */
final class ArchitectureRules {

    /** 실행 모듈의 패키지 이름 (inbound-api, worker, reconciler, admin-api) */
    static final List<String> EXECUTABLE_MODULES = List.of("inbound", "worker", "reconciler", "admin");

    private ArchitectureRules() {
    }

    static ArchRule coreIsFrameworkFree(String root) {
        return noClasses().that().resideInAPackage(root + ".core..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..", "javax.persistence..", "org.hibernate..",
                        "software.amazon.awssdk..", "com.amazonaws..", "io.awspring..")
                .because("core는 외부 의존이 없는 도메인 모듈이다. "
                        + "고치는 방법: Spring/JPA/AWS가 필요한 코드는 core에 포트(interface)만 두고 구현은 infra로 옮긴다");
    }

    static ArchRule executableModulesDoNotReferenceEachOther(String root) {
        List<ArchRule> rules = new ArrayList<>();
        for (String from : EXECUTABLE_MODULES) {
            for (String to : EXECUTABLE_MODULES) {
                if (from.equals(to)) {
                    continue;
                }
                rules.add(noClasses().that().resideInAPackage(root + "." + from + "..")
                        .should().dependOnClassesThat().resideInAPackage(root + "." + to + "..")
                        // 픽스처처럼 일부 모듈만 있는 경우를 위해 허용한다.
                        // 실제 코드에 모듈 패키지가 존재하는지는 ArchitectureTest에서 따로 검사한다
                        .allowEmptyShould(true));
            }
        }
        // CompositeArchRule은 하위 규칙의 because를 실패 메시지에 넣지 않으므로 묶음 규칙에 붙인다
        return CompositeArchRule.of(rules)
                .as("실행 모듈(inbound, worker, reconciler, admin)끼리 서로 참조하지 않는다")
                .because("실행 모듈은 독립적으로 배포된다. "
                        + "고치는 방법: 공통 로직은 core(도메인·포트) 또는 infra(어댑터)로 옮기고 양쪽에서 그것을 쓴다");
    }

    static ArchRule shopbyAdapterIsReachedOnlyThroughPort(String root) {
        return noClasses().that().resideInAnyPackage(executableAndCorePackages(root))
                .should().dependOnClassesThat().resideInAPackage(root + ".infra.shopby..")
                .because("샵바이 호출은 전역 레이트 리미터를 거치는 ShopbyClient 하나로만 나간다(ADR-0004). "
                        + "고치는 방법: infra.shopby 클래스 대신 core.port.ShopbyClient를 주입받아 호출한다");
    }

    static ArchRule shopbyInternalsAreEncapsulated(String root) {
        return classes().that().resideInAPackage(root + ".infra.shopby..")
                .and().doNotImplement(ShopbyClient.class)
                .should().onlyBeAccessed().byClassesThat().resideInAPackage(root + ".infra.shopby..")
                .because("infra.shopby 내부 클래스(HTTP 호출, 토큰 버킷 등)를 밖에서 직접 쓰면 레이트 리미터를 우회한다. "
                        + "고치는 방법: ShopbyClient 구현체에 메서드를 추가하고 그것을 통해 호출한다")
                // ShopbyClient 구현은 레이트 리미터 기능(F-07)에서 만든다. 그 전까지는 검사 대상 클래스가 없다.
                // 규칙이 실제로 위반을 잡는지는 ArchitectureRulesViolationTest가 픽스처로 확인한다
                .allowEmptyShould(true);
    }

    static ArchRule shopbyClientImplementationsLiveInShopbyAdapter(String root) {
        return classes().that().implement(ShopbyClient.class)
                .should().resideInAPackage(root + ".infra.shopby..")
                .because("ShopbyClient 구현체가 infra.shopby 밖에 있으면 레이트 리미터를 거치지 않는 두 번째 출구가 생긴다. "
                        + "고치는 방법: 구현체를 infra.shopby로 옮기고 레이트 리미터를 거쳐 호출한다")
                // 구현체는 F-07에서 만든다. 그때부터는 구현체가 반드시 infra.shopby에 있어야 하므로
                // shopbyInternalsAreEncapsulated도 빈 집합으로 통과할 수 없다
                .allowEmptyShould(true);
    }

    /** HTTP 클라이언트 사용을 허용하는 패키지: 샵바이 어댑터, 3PL 어댑터, mock 서버 */
    static String[] httpAllowedPackages(String root) {
        return new String[] {root + ".infra.shopby..", root + ".infra.tpl..", root + ".mock.."};
    }

    static ArchRule httpClientsOnlyInAdapterPackages(String root) {
        DescribedPredicate<JavaClass> httpClientTypes = JavaClass.Predicates.resideInAnyPackage(
                        "org.springframework.web.client..",
                        "org.springframework.web.reactive.function.client..",
                        "org.springframework.http.client..",
                        "org.springframework.web.service..",
                        "org.springframework.cloud.openfeign..",
                        "java.net.http..",
                        "okhttp3..", "feign..",
                        "reactor.netty.http.client..",
                        "org.apache.hc..", "org.apache.http..")
                .or(JavaClass.Predicates.assignableTo(URLConnection.class))
                .as("HTTP 클라이언트 타입");
        return noClasses().that().resideInAPackage(root + "..")
                .and().resideOutsideOfPackages(httpAllowedPackages(root))
                .should().dependOnClassesThat(httpClientTypes)
                .because("외부 HTTP 호출은 infra.shopby(샵바이)와 infra.tpl(3PL) 어댑터에서만 한다. "
                        + "다른 곳에서 HTTP를 쓰면 샵바이 호출이 ShopbyClient와 레이트 리미터를 우회할 수 있다. "
                        + "고치는 방법: core에 포트를 두고 해당 어댑터 패키지에 구현한다");
    }

    private static String[] executableAndCorePackages(String root) {
        List<String> packages = new ArrayList<>();
        packages.add(root + ".core..");
        EXECUTABLE_MODULES.forEach(m -> packages.add(root + "." + m + ".."));
        return packages.toArray(String[]::new);
    }
}
