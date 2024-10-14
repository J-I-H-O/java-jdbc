package transaction.stage2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.NestedTransactionNotSupportedException;

/**
 * 트랜잭션 전파(Transaction Propagation)란?
 * 트랜잭션의 경계에서 이미 진행 중인 트랜잭션이 있을 때 또는 없을 때 어떻게 동작할 것인가를 결정하는 방식을 말한다.
 *
 * FirstUserService 클래스의 메서드를 실행할 때 첫 번째 트랜잭션이 생성된다.
 * SecondUserService 클래스의 메서드를 실행할 때 두 번째 트랜잭션이 어떻게 되는지 관찰해보자.
 *
 * https://docs.spring.io/spring-framework/docs/current/reference/html/data-access.html#tx-propagation
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Stage2Test {

    private static final Logger log = LoggerFactory.getLogger(Stage2Test.class);

    @Autowired
    private FirstUserService firstUserService;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void tearDown() {
        userRepository.deleteAll();
    }

    /**
     * 생성된 트랜잭션이 몇 개인가?
     * - 1개
     * 왜 그런 결과가 나왔을까?
     * - REQUIRED 전파 속성은 내부 트랜잭션이 외부 트랜잭션에 참여하기 때문.
     * - 기존에 이미 진행중이던 논리 트랜잭션(saveFirstTransactionWithRequired)이 있으므로 새로운 트랜잭션을 생성하지 않고 기존 트랜잭션에 참여
     */
    @Test
    void testRequired() {
        final var actual = firstUserService.saveFirstTransactionWithRequired();

        log.info("transactions : {}", actual);
        assertThat(actual)
                .hasSize(1)
                .containsExactly("transaction.stage2.FirstUserService.saveFirstTransactionWithRequired");
    }

    /**
     * 생성된 트랜잭션이 몇 개인가?
     * - 2개
     * 왜 그런 결과가 나왔을까?
     * - REQUIRES_NEW 전파 속성은 새로운 트랜잭션이 기존 트랜잭션과는 다른 별도의 트랜잭션을 사용하기 때문
     */
    @Test
    void testRequiredNew() {
        final var actual = firstUserService.saveFirstTransactionWithRequiredNew();

        log.info("transactions : {}", actual);
        assertThat(actual)
                .hasSize(2)
                .containsExactlyInAnyOrder("transaction.stage2.FirstUserService.saveFirstTransactionWithRequiredNew",
                        "transaction.stage2.SecondUserService.saveSecondTransactionWithRequiresNew");
    }

    /**
     * firstUserService.saveAndExceptionWithRequiredNew()에서 강제로 예외를 발생시킨다.
     * REQUIRES_NEW 일 때 예외로 인한 롤백이 발생하면서 어떤 상황이 발생하는 지 확인해보자.
     * - 외부 트랜잭션과 내부 트랜잭션이 별도의 물리 트랜잭션을 사용하기 때문에, 서로의 커밋/롤백이 영향을 주지 않음
     */
    @Test
    void testRequiredNewWithRollback() {
        assertThat(firstUserService.findAll()).hasSize(0);

        // 외부 트랜잭션은 예외가 발생해 롤백되었지만, 내부 트랜잭션은 그대로 커밋됨
        assertThatThrownBy(() -> firstUserService.saveAndExceptionWithRequiredNew())
                .isInstanceOf(RuntimeException.class);

        assertThat(firstUserService.findAll()).hasSize(1);
    }

    /**
     * FirstUserService.saveFirstTransactionWithSupports() 메서드를 보면 @Transactional이 주석으로 되어 있다.
     * 주석인 상태에서 테스트를 실행했을 때와 주석을 해제하고 테스트를 실행했을 때 어떤 차이점이 있는지 확인해보자.
     * - 1. @Transactional이 주석처리 된 경우 = 외부 트랜잭션이 존재하지 않는 경우
     *      - SUPPORTS 전파 속성일 때, 외부 트랜잭션이 존재하지 않으면 물리 트랜잭션 없이 실행됨
     * - 2. @Transactional이 존재하는 경우 = 외부 트랜잭션이 존재하는 경우
     *      - SUPPORTS 전파 속성일 때, 외부 트랜잭션이 존재하면 외부 트랜잭션에 참여함 (먼저 실행된 논리 트랜잭션을 그대로 사용)
     */
    @Test
    void testSupports() {
        final var actual = firstUserService.saveFirstTransactionWithSupports();

        log.info("transactions : {}", actual);

        // 1. @Transactional을 주석처리 한 경우 (외부 트랜잭션이 존재하지 않는 경우)
        /** TODO: SUPPORTS는 외부 트랜잭션이 없을 때 트랜잭션을 사용하지 않기 때문에 active 는 false.
         *   그러나 TransactionSynchronizationManager.getCurrentTransactionName()의 결과로 transaction.stage2.SecondUserService.saveSecondTransactionWithSupports가 조회됨.
         *   트랜잭션 동기화 더 찾아보자.
         *   Note: For transaction managers with transaction synchronization, SUPPORTS is slightly different from no transaction at all, as it defines a transaction scope that synchronization will apply for. As a consequence, the same resources (JDBC Connection, Hibernate Session, etc) will be shared for the entire specified scope. Note that this depends on the actual synchronization configuration of the transaction manager.
         */
        assertThat(actual)
                .hasSize(1)
                .containsExactly("transaction.stage2.SecondUserService.saveSecondTransactionWithSupports");

//        // 2. @Transactional 주석을 해제한 경우 (외부 트랜잭션이 존재하는 경우)
//        assertThat(actual)
//                .hasSize(1)
//                .containsExactly("transaction.stage2.FirstUserService.saveFirstTransactionWithSupports");
    }

    /**
     * FirstUserService.saveFirstTransactionWithMandatory() 메서드를 보면 @Transactional이 주석으로 되어 있다.
     * 주석인 상태에서 테스트를 실행했을 때와 주석을 해제하고 테스트를 실행했을 때 어떤 차이점이 있는지 확인해보자.
     * SUPPORTS와 어떤 점이 다른지도 같이 챙겨보자.
     * - 1. @Transactional이 주석처리 된 경우 = 외부 트랜잭션이 존재하지 않는 경우
     *      - MANDATORY 전파 속성일 때, 외부 트랜잭션이 존재하지 않으면 IllegalTransactionStateException 발생
     * - 2. @Transactional이 존재하는 경우 = 외부 트랜잭션이 존재하는 경우
     *      - MANDATORY 전파 속성일 때, 외부 트랜잭션이 존재하면 외부 트랜잭션에 참여함 (먼저 실행된 논리 트랜잭션을 그대로 사용)
     */
    @Test
    void testMandatory() {
        // 1. @Transactional을 주석처리 한 경우 (외부 트랜잭션이 존재하지 않는 경우)
        assertThatThrownBy(() -> firstUserService.saveFirstTransactionWithMandatory())
                .isExactlyInstanceOf(IllegalTransactionStateException.class);

//        // 2. @Transactional 주석을 해제한 경우 (외부 트랜잭션이 존재하는 경우)
//        final var actual = firstUserService.saveFirstTransactionWithMandatory();
//
//        log.info("transactions : {}", actual);
//
//        assertThat(actual)
//                .hasSize(1)
//                .containsExactly("transaction.stage2.FirstUserService.saveFirstTransactionWithMandatory");
    }

    /**
     * - 1. @Transactional이 주석처리 된 경우 = 외부 트랜잭션이 존재하지 않는 경우
     *      - NOT_SUPPORTED 전파 속성일 때, 외부 트랜잭션이 존재하지 않으면 물리 트랜잭션 없이 실행됨
     * - 2. @Transactional이 존재하는 경우 = 외부 트랜잭션이 존재하는 경우
     *      - NOT_SUPPORTED 전파 속성일 때, 외부 트랜잭션이 존재하면 외부 트랜잭션을 대기시키고 물리 트랜잭션 없이 실행됨
     *
     * 아래 테스트는 몇 개의 물리적 트랜잭션이 동작할까?
     * - 1개
     *
     * FirstUserService.saveFirstTransactionWithNotSupported() 메서드의 @Transactional을 주석 처리하자.
     * 다시 테스트를 실행하면 몇 개의 물리적 트랜잭션이 동작할까?
     * - 0개 (물리적 트랜잭션을 사용하지 않음)
     *
     * 스프링 공식 문서에서 물리적 트랜잭션과 논리적 트랜잭션의 차이점이 무엇인지 찾아보자.
     */
    @Test
    void testNotSupported() {
        final var actual = firstUserService.saveFirstTransactionWithNotSupported();
        log.info("transactions : {}", actual);

//        // 1. @Transactional을 주석처리 한 경우 (외부 트랜잭션이 존재하지 않는 경우)
//        assertThat(actual)
//                .hasSize(1)
//                .containsExactlyInAnyOrder("transaction.stage2.SecondUserService.saveSecondTransactionWithNotSupported");

        // 2. @Transactional 주석을 해제한 경우 (외부 트랜잭션이 존재하는 경우)
        assertThat(actual)
                .hasSize(2)
                .containsExactlyInAnyOrder("transaction.stage2.FirstUserService.saveFirstTransactionWithNotSupported",
                        "transaction.stage2.SecondUserService.saveSecondTransactionWithNotSupported");
    }

    /**
     * 아래 테스트는 왜 실패할까?
     * - NESTED는 JDBC의 savepoint 기능을 사용하는데, JPA에서는 이를 지원하지 않기 때문
     * - Note: Actual creation of a nested transaction will only work on specific transaction managers. Out of the box, this only applies to the JDBC DataSourceTransactionManager. Some JTA providers might support nested transactions as well.
     *
     * FirstUserService.saveFirstTransactionWithNested() 메서드의 @Transactional을 주석 처리하면 어떻게 될까?
     * - NESTED는 외부 트랜잭션이 없는 경우 REQUIRED와 유사하게 동작함
     * - Execute within a nested transaction if a current transaction exists, behave like REQUIRED otherwise.
     */
    @Test
    void testNested() {
//        // 1. @Transactional을 주석처리 한 경우 (외부 트랜잭션이 존재하지 않는 경우) -> REQUIRED와 유사하게 동작
//        final var actual = firstUserService.saveFirstTransactionWithNested();
//
//        log.info("transactions : {}", actual);
//        assertThat(actual)
//                .hasSize(1)
//                .containsExactly("transaction.stage2.SecondUserService.saveSecondTransactionWithNested");

        // 2. @Transactional 주석을 해제한 경우 (외부 트랜잭션이 존재하는 경우)
        assertThatThrownBy(() -> firstUserService.saveFirstTransactionWithNested())
                .isExactlyInstanceOf(NestedTransactionNotSupportedException.class);
    }

    /**
     * 마찬가지로 @Transactional을 주석처리하면서 관찰해보자.
     * - 1. @Transactional이 주석처리 된 경우 = 외부 트랜잭션이 존재하지 않는 경우
     *      - NEVER 전파 속성일 때, 외부 트랜잭션이 존재하지 않으면 물리 트랜잭션 없이 실행됨
     * - 2. @Transactional이 존재하는 경우 = 외부 트랜잭션이 존재하는 경우
     *      - NEVER 전파 속성일 때, 외부 트랜잭션이 존재하면 IllegalTransactionStateException 발생
     */
    @Test
    void testNever() {
//        // 1. @Transactional을 주석처리 한 경우 (외부 트랜잭션이 존재하지 않는 경우)
//        final var actual = firstUserService.saveFirstTransactionWithNever();
//
//        log.info("transactions : {}", actual);
//        assertThat(actual)
//                .hasSize(1)
//                .containsExactly("transaction.stage2.SecondUserService.saveSecondTransactionWithNever");

        // 2. @Transactional 주석을 해제한 경우 (외부 트랜잭션이 존재하는 경우)
        assertThatThrownBy(() -> firstUserService.saveFirstTransactionWithNever())
                .isExactlyInstanceOf(IllegalTransactionStateException.class);
    }
}
