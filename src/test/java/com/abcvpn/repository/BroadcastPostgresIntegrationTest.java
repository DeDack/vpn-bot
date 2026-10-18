package com.abcvpn.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.abcvpn.AbcVpnApplication;
import com.abcvpn.enumeration.BroadcastSegment;
import com.abcvpn.exception.FlowException;
import com.abcvpn.service.ReturnBonusService;
import com.abcvpn.service.SubscriptionService;
import com.abcvpn.service.VpnAccessService;
import com.abcvpn.service.WalletService;
import com.abcvpn.service.PaymentService;
import com.abcvpn.service.ReferralService;
import com.abcvpn.service.TariffService;
import com.abcvpn.service.PlategaClient;
import com.abcvpn.config.AbcVpnProperties;
import com.abcvpn.config.PlategaProperties;
import com.abcvpn.dto.PlategaCallback;
import com.abcvpn.enumeration.WalletTransactionType;

@Testcontainers(disabledWithoutDocker=true)
class BroadcastPostgresIntegrationTest {
    private static final Instant NOW=Instant.parse("2026-10-11T10:00:00Z");
    @Container
    private static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    private static DriverManagerDataSource dataSource;
    private static boolean referralTariffsCreated;
    private JdbcTemplate jdbc;
    private BroadcastRepository broadcasts;

    @BeforeAll
    static void migrateAndStartApplicationTwice() {
        dataSource=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        startApplication("--spring.liquibase.contexts=!referral-wallet").close();
        new JdbcTemplate(dataSource).update("""
                INSERT INTO tariffs(code,name,duration_days,price_rub,device_limit,remnawave_squad_id)
                VALUES('BASIC','1 месяц',30,150,10,?)
                """,UUID.randomUUID());
        startApplication().close();
        referralTariffsCreated=new JdbcTemplate(dataSource).queryForObject("""
                SELECT COUNT(*)=2 FROM tariffs WHERE code IN ('REFERRAL_1D','REFERRAL_7D')
                """,Boolean.class);
        startApplication().close();
    }

    @AfterAll
    static void clearDataSource() {dataSource=null;}

    @BeforeEach
    void resetDatabase() {
        jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE wallet_transactions,wallet_accounts,return_bonuses,broadcast_deliveries,broadcast_segments,broadcasts,analytics_events,payments,subscriptions,tariffs,users,campaigns RESTART IDENTITY CASCADE");
        broadcasts=new BroadcastRepository(jdbc);
    }

    @Test
    void migrationIsAppliedAndRequiredConstraintsAndIndexExist() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM databasechangelog WHERE id='009-broadcasts'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM databasechangelog WHERE id='010-tariff-prices'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM databasechangelog WHERE id='011-referral-wallet'",Integer.class)).isEqualTo(1);
        assertThat(referralTariffsCreated).isTrue();
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename='broadcast_deliveries'",String.class))
                .contains("broadcast_deliveries_queue_idx","broadcast_deliveries_broadcast_id_telegram_id_key");
        assertThat(jdbc.queryForList("SELECT conname FROM pg_constraint WHERE conrelid='broadcast_deliveries'::regclass",String.class))
                .contains("broadcast_deliveries_broadcast_id_fkey","broadcast_deliveries_telegram_id_fkey","broadcast_deliveries_status_check");
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename='wallet_transactions'",String.class))
                .contains("wallet_transactions_reference_key_key","wallet_referral_reward_payment_idx","wallet_reserve_payment_idx");
    }

    @Test
    void walletMigrationFailsFastWithoutBasicTariff() {
        String schema="wallet_without_basic";
        jdbc.execute("CREATE SCHEMA "+schema);
        String url=POSTGRES.getJdbcUrl()+"&currentSchema="+schema;
        try {
            startApplication("--spring.datasource.url="+url,"--spring.liquibase.contexts=!referral-wallet").close();
            assertThatThrownBy(()->startApplication("--spring.datasource.url="+url))
                    .hasRootCauseInstanceOf(liquibase.exception.PreconditionFailedException.class);
        } finally {
            jdbc.execute("DROP SCHEMA "+schema+" CASCADE");
        }
    }

    @Test
    void concurrentFullBalancePurchaseDebitsAndExtendsOnlyOnce() throws Exception {
        insertUser(1,false);
        long tariffId=jdbc.queryForObject("""
                INSERT INTO tariffs(code,name,duration_days,price_rub,device_limit,remnawave_squad_id)
                VALUES('BASIC','1 месяц',30,150,10,?) RETURNING id
                """,Long.class,UUID.randomUUID());
        WalletRepository walletRepository=new WalletRepository(jdbc);walletRepository.ensure(1);
        walletRepository.addCompleted(1,30_000,"CREDIT",WalletTransactionType.ADMIN_ADJUSTMENT,
                "test-credit",null,null,"test",NOW);walletRepository.credit(1,30_000);
        PaymentRepository payments=new PaymentRepository(jdbc);SubscriptionRepository subscriptions=new SubscriptionRepository(jdbc);
        WalletService service=new WalletService(walletRepository,payments,
                new SubscriptionService(subscriptions,Clock.fixed(NOW,ZoneOffset.UTC)),properties(),Clock.fixed(NOW,ZoneOffset.UTC));
        TransactionTemplate transaction=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TariffRepository tariffs=new TariffRepository(jdbc);var tariff=tariffs.findById(tariffId).orElseThrow();
        CountDownLatch start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var first=workers.submit(()->purchase(transaction,service,tariff,start));
            var second=workers.submit(()->purchase(transaction,service,tariff,start));
            start.countDown();assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(false,true);
        }
        assertThat(walletRepository.find(1).availableKopecks()).isEqualTo(15_000);
        assertThat(subscriptions.findByTelegramId(1).orElseThrow().expiresAt()).isEqualTo(NOW.plusSeconds(30L*86400));
        assertThat(subscriptions.findByTelegramId(1).orElseThrow().syncStatus().name()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_transactions WHERE type='SUBSCRIPTION_PAYMENT'",Integer.class)).isEqualTo(1);
    }

    @Test
    void mixedPaymentCapturesReserveAndRewardsReferrerOnlyOnce() {
        insertUser(1,false);insertUser(2,false);
        jdbc.update("UPDATE users SET referrer_telegram_id=1,source_type='REFERRAL' WHERE telegram_id=2");
        long tariffId=jdbc.queryForObject("""
                INSERT INTO tariffs(code,name,duration_days,price_rub,device_limit,remnawave_squad_id)
                VALUES('BASIC','1 месяц',30,150,10,?) RETURNING id
                """,Long.class,UUID.randomUUID());
        WalletRepository walletRepository=new WalletRepository(jdbc);walletRepository.ensure(2);
        walletRepository.addCompleted(2,5_000,"CREDIT",WalletTransactionType.ADMIN_ADJUSTMENT,
                "mixed-credit",null,null,"test",NOW);walletRepository.credit(2,5_000);
        PaymentRepository payments=new PaymentRepository(jdbc);SubscriptionRepository subscriptions=new SubscriptionRepository(jdbc);
        SubscriptionService subscriptionService=new SubscriptionService(subscriptions,Clock.fixed(NOW,ZoneOffset.UTC));
        WalletService walletService=new WalletService(walletRepository,payments,subscriptionService,properties(),Clock.fixed(NOW,ZoneOffset.UTC));
        var tariff=new TariffRepository(jdbc).findById(tariffId).orElseThrow();
        var preparation=new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .execute(status->walletService.preparePurchase(2,tariff,"mixed-action"));
        UUID transactionId=UUID.randomUUID();payments.attachProviderData(preparation.payment().id(),transactionId,"https://pay",NOW.plusSeconds(900));
        UserRepository users=new UserRepository(jdbc);
        ReferralService referrals=new ReferralService(users,walletRepository,walletService,Clock.fixed(NOW,ZoneOffset.UTC));
        PaymentService paymentService=new PaymentService(payments,new TariffService(new TariffRepository(jdbc)),new TariffRepository(jdbc),
                subscriptionService,referrals,mock(PlategaClient.class),new PlategaProperties(true,"https://pay","merchant","secret"),
                properties(),users,walletService,Clock.fixed(NOW,ZoneOffset.UTC));
        PlategaCallback callback=new PlategaCallback(transactionId,new BigDecimal("100"),"RUB","CONFIRMED",2);
        TransactionTemplate transaction=new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transaction.executeWithoutResult(status->paymentService.processCallback(callback));
        transaction.executeWithoutResult(status->paymentService.processCallback(callback));

        assertThat(walletRepository.find(2).availableKopecks()).isZero();
        assertThat(walletRepository.find(2).reservedKopecks()).isZero();
        assertThat(walletRepository.find(2).totalSpentKopecks()).isEqualTo(5_000);
        assertThat(walletRepository.find(1).availableKopecks()).isEqualTo(5_000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_transactions WHERE type='REFERRAL_REWARD'",Integer.class)).isEqualTo(1);
        assertThat(subscriptions.findByTelegramId(2).orElseThrow().expiresAt()).isEqualTo(NOW.plusSeconds(30L*86400));
        assertThat(walletRepository.referralStats(1,NOW)).isEqualTo(new WalletRepository.ReferralStats(1,1));
    }

    @Test
    void terminalCallbackWithZeroAmountReleasesReserveOnlyOnce() {
        insertUser(1,false);
        long tariffId=jdbc.queryForObject("""
                INSERT INTO tariffs(code,name,duration_days,price_rub,device_limit,remnawave_squad_id)
                VALUES('BASIC','1 месяц',30,150,10,?) RETURNING id
                """,Long.class,UUID.randomUUID());
        WalletRepository walletRepository=new WalletRepository(jdbc);walletRepository.ensure(1);
        walletRepository.addCompleted(1,5_000,"CREDIT",WalletTransactionType.ADMIN_ADJUSTMENT,
                "terminal-credit",null,null,"test",NOW);walletRepository.credit(1,5_000);
        PaymentRepository payments=new PaymentRepository(jdbc);SubscriptionRepository subscriptions=new SubscriptionRepository(jdbc);
        SubscriptionService subscriptionService=new SubscriptionService(subscriptions,Clock.fixed(NOW,ZoneOffset.UTC));
        WalletService walletService=new WalletService(walletRepository,payments,subscriptionService,properties(),Clock.fixed(NOW,ZoneOffset.UTC));
        var tariff=new TariffRepository(jdbc).findById(tariffId).orElseThrow();
        var preparation=new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .execute(status->walletService.preparePurchase(1,tariff,"terminal-action"));
        UUID transactionId=UUID.randomUUID();payments.attachProviderData(preparation.payment().id(),transactionId,"https://pay",NOW.plusSeconds(900));
        UserRepository users=new UserRepository(jdbc);
        ReferralService referrals=new ReferralService(users,walletRepository,walletService,Clock.fixed(NOW,ZoneOffset.UTC));
        PaymentService paymentService=new PaymentService(payments,new TariffService(new TariffRepository(jdbc)),new TariffRepository(jdbc),
                subscriptionService,referrals,mock(PlategaClient.class),new PlategaProperties(true,"https://pay","merchant","secret"),
                properties(),users,walletService,Clock.fixed(NOW,ZoneOffset.UTC));
        PlategaCallback callback=new PlategaCallback(transactionId,BigDecimal.ZERO,"RUB","FAILED",2);
        TransactionTemplate transaction=new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transaction.executeWithoutResult(status->paymentService.processCallback(callback));
        transaction.executeWithoutResult(status->paymentService.processCallback(callback));

        assertThat(walletRepository.find(1).availableKopecks()).isEqualTo(5_000);
        assertThat(walletRepository.find(1).reservedKopecks()).isZero();
        assertThat(payments.findById(preparation.payment().id()).orElseThrow().status().name()).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_transactions WHERE type='SUBSCRIPTION_PAYMENT_RELEASE'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM subscriptions",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_transactions WHERE type='REFERRAL_REWARD'",Integer.class)).isZero();
    }

    @Test
    void audienceQueueDeduplicatesAndHonorsMarketingOptOut() {
        insertUser(1,false);insertUser(2,true);
        long id=createBroadcast(true);

        assertThat(broadcasts.audience(Set.of(BroadcastSegment.ALL),true).recipients()).isEqualTo(1);
        assertThat(broadcasts.queue(id,Set.of(BroadcastSegment.ALL),true,false)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM broadcast_deliveries WHERE broadcast_id=?",Integer.class,id)).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("INSERT INTO broadcast_deliveries(broadcast_id,telegram_id) VALUES(?,?)",id,1))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void optOutAfterQueueMakesClaimIneligible() {
        insertUser(1,false);long id=createBroadcast(true);
        broadcasts.queue(id,Set.of(BroadcastSegment.ALL),true,false);
        jdbc.update("UPDATE users SET marketing_opt_out=TRUE WHERE telegram_id=1");

        List<BroadcastRepository.Delivery> claimed=broadcasts.claim(20);

        assertThat(claimed).singleElement().satisfies(delivery->assertThat(delivery.eligible()).isFalse());
    }

    @Test
    void concurrentWorkersDoNotClaimSameDeliveryAndStaleSendingIsRecovered() throws Exception {
        insertUser(1,false);long id=createBroadcast(false);
        broadcasts.queue(id,Set.of(BroadcastSegment.ALL),false,false);
        CountDownLatch start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var first=workers.submit(()->{start.await();return new BroadcastRepository(new JdbcTemplate(dataSource)).claim(20);});
            var second=workers.submit(()->{start.await();return new BroadcastRepository(new JdbcTemplate(dataSource)).claim(20);});
            start.countDown();
            List<BroadcastRepository.Delivery> a=first.get(10,TimeUnit.SECONDS);
            List<BroadcastRepository.Delivery> b=second.get(10,TimeUnit.SECONDS);
            assertThat(a.size()+b.size()).isEqualTo(1);
        }

        jdbc.update("UPDATE broadcast_deliveries SET status='SENDING',updated_at=NOW()-INTERVAL '6 minutes'");
        List<BroadcastRepository.Delivery> recovered=broadcasts.claim(20);

        assertThat(recovered).singleElement().satisfies(delivery->{
            assertThat(delivery.recovered()).isTrue();
            assertThat(delivery.attempts()).isEqualTo(2);
        });
    }

    @Test
    void returnBonusIsGrantedOnceAndLeavesSubscriptionPendingForExternalRetry() throws Exception {
        insertUser(1,false);
        jdbc.update("UPDATE users SET trial_used=TRUE,created_at=NOW() WHERE telegram_id=1");
        long tariffId=jdbc.queryForObject("""
                INSERT INTO tariffs(code,name,duration_days,price_rub,device_limit,remnawave_squad_id)
                VALUES('TRIAL','Trial',3,0,1,?) RETURNING id
                """,Long.class,UUID.randomUUID());
        jdbc.update("INSERT INTO subscriptions(telegram_id,tariff_id,expires_at,sync_status) VALUES(1,?,?,'SYNCED')",
                tariffId,Timestamp.from(NOW.minusSeconds(60)));
        ReturnBonusService service=returnBonusService();
        TransactionTemplate transaction=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CountDownLatch start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var first=workers.submit(()->activate(transaction,service,start));
            var second=workers.submit(()->activate(transaction,service,start));
            start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("GRANTED","REJECTED");
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_bonuses WHERE telegram_id=1",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sync_status FROM subscriptions WHERE telegram_id=1",String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT expires_at FROM subscriptions WHERE telegram_id=1",Timestamp.class).toInstant())
                .isEqualTo(NOW.plusSeconds(2*86400));
        VpnAccessService vpnAccessService=mock(VpnAccessService.class);
        var pending=new SubscriptionRepository(jdbc).findByTelegramId(1).orElseThrow();
        doThrow(new IllegalStateException("Remnawave unavailable")).when(vpnAccessService).sync(pending);
        assertThatThrownBy(()->vpnAccessService.sync(pending)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT sync_status FROM subscriptions WHERE telegram_id=1",String.class)).isEqualTo("PENDING");
    }

    private static ConfigurableApplicationContext startApplication() {
        return startApplication(new String[0]);
    }

    private static ConfigurableApplicationContext startApplication(String... extraArguments) {
        List<String> arguments=new java.util.ArrayList<>(List.of(
                "--server.port=0",
                "--spring.datasource.url="+POSTGRES.getJdbcUrl(),
                "--spring.datasource.username="+POSTGRES.getUsername(),
                "--spring.datasource.password="+POSTGRES.getPassword(),
                "--abc-vpn.telegram.enabled=false",
                "--abc-vpn.admin-password=test-password",
                "--abc-vpn.admin-session-secret=test-session-secret-test-session-secret"));
        arguments.addAll(List.of(extraArguments));
        return new SpringApplicationBuilder(AbcVpnApplication.class).web(WebApplicationType.SERVLET).run(
                arguments.toArray(String[]::new));
    }

    private long createBroadcast(boolean marketing) {
        long id=broadcasts.create("SERVICE_UPDATE","message",null,null,null,marketing);
        broadcasts.addSegments(id,Set.of(BroadcastSegment.ALL));
        return id;
    }

    private void insertUser(long telegramId,boolean optedOut) {
        jdbc.update("""
                INSERT INTO users(telegram_id,username,first_name,source_type,referral_code,marketing_opt_out)
                VALUES(?,?,'User','ORGANIC',?,?)
                """,telegramId,"user"+telegramId,"ref"+telegramId,optedOut);
    }

    private ReturnBonusService returnBonusService() {
        UserRepository users=new UserRepository(jdbc);
        SubscriptionRepository subscriptions=new SubscriptionRepository(jdbc);
        SubscriptionService subscriptionService=new SubscriptionService(subscriptions,Clock.fixed(NOW,ZoneOffset.UTC));
        return new ReturnBonusService(users,subscriptions,new TariffRepository(jdbc),new PaymentRepository(jdbc),
                new ReturnBonusRepository(jdbc),subscriptionService,Clock.fixed(NOW,ZoneOffset.UTC));
    }

    private String activate(TransactionTemplate transaction,ReturnBonusService service,CountDownLatch start) throws InterruptedException {
        start.await();
        try {transaction.executeWithoutResult(status->service.activate(1));return "GRANTED";}
        catch(FlowException exception) {return "REJECTED";}
    }
    private boolean purchase(TransactionTemplate transaction,WalletService service,com.abcvpn.entity.Tariff tariff,CountDownLatch start) throws InterruptedException {
        start.await();return transaction.execute(status->service.preparePurchase(1,tariff,"same-action").reused());
    }
    private AbcVpnProperties properties() {
        return new AbcVpnProperties(new AbcVpnProperties.Telegram("",0,"bot",false),new AbcVpnProperties.Remnawave("","","",""),
                "https://vpn.test","https://api.test","@support","p","t","1","1","password-123","session-secret-123",2,7);
    }
}
