/**
 * Copyright 2017 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.timelock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.util.SortedMap;

import javax.net.ssl.SSLSocketFactory;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.palantir.atlasdb.http.AtlasDbHttpClients;
import com.palantir.lock.LockDescriptor;
import com.palantir.lock.LockMode;
import com.palantir.lock.LockRefreshToken;
import com.palantir.lock.LockRequest;
import com.palantir.lock.RemoteLockService;
import com.palantir.lock.StringLockDescriptor;
import com.palantir.timestamp.TimestampManagementService;
import com.palantir.timestamp.TimestampService;

import io.dropwizard.testing.ResourceHelpers;

public class PaxosTimeLockServerIntegrationTest {
    private static final String NOT_FOUND_CODE = "404";

    private static final String CLIENT_1 = "test";
    private static final String CLIENT_2 = "test2";
    private static final String NONEXISTENT_CLIENT = "nonexistent";
    private static final String INVALID_CLIENT = "test2\b";

    private static final long ONE_MILLION = 1000000;
    private static final long TWO_MILLION = 2000000;
    private static final int FORTY_TWO = 42;

    private static final Optional<SSLSocketFactory> NO_SSL = Optional.absent();
    private static final String LOCK_CLIENT_NAME = "lock-client-name";
    private static final SortedMap<LockDescriptor, LockMode> LOCK_MAP =
            ImmutableSortedMap.of(StringLockDescriptor.of("lock1"), LockMode.WRITE);
    private static final File TIMELOCK_CONFIG_TEMPLATE =
            new File(ResourceHelpers.resourceFilePath("paxosSingleServer.yml"));

    private static final TemporaryFolder TEMPORARY_FOLDER = new TemporaryFolder();
    private static final TemporaryConfigurationHolder TEMPORARY_CONFIG_HOLDER =
            new TemporaryConfigurationHolder(TEMPORARY_FOLDER, TIMELOCK_CONFIG_TEMPLATE);
    private static final TimeLockServerHolder TIMELOCK_SERVER_HOLDER =
            new TimeLockServerHolder(TEMPORARY_CONFIG_HOLDER::getTemporaryConfigFileLocation);

    private final TimestampService timestampService = getTimestampService(CLIENT_1);
    private final TimestampManagementService timestampManagementService = getTimestampManagementService(CLIENT_1);

    @ClassRule
    public static final RuleChain ruleChain = RuleChain.outerRule(TEMPORARY_FOLDER)
            .around(TEMPORARY_CONFIG_HOLDER)
            .around(TIMELOCK_SERVER_HOLDER);

    @Test
    public void lockServiceShouldAllowUsToTakeOutLocks() throws InterruptedException {
        RemoteLockService lockService = getLockService(CLIENT_1);

        LockRefreshToken token = lockService.lock(LOCK_CLIENT_NAME, LockRequest.builder(LOCK_MAP)
                .doNotBlock()
                .build());

        assertThat(token).isNotNull();

        lockService.unlock(token);
    }

    @Test
    public void lockServiceShouldAllowUsToTakeOutSameLockInDifferentNamespaces() throws InterruptedException {
        RemoteLockService lockService1 = getLockService(CLIENT_1);
        RemoteLockService lockService2 = getLockService(CLIENT_2);

        LockRefreshToken token1 = lockService1.lock(LOCK_CLIENT_NAME, LockRequest.builder(LOCK_MAP)
                .doNotBlock()
                .build());
        LockRefreshToken token2 = lockService2.lock(LOCK_CLIENT_NAME, LockRequest.builder(LOCK_MAP)
                .doNotBlock()
                .build());

        assertThat(token1).isNotNull();
        assertThat(token2).isNotNull();

        lockService1.unlock(token1);
        lockService2.unlock(token2);
    }

    @Test
    public void lockServiceShouldNotAllowUsToRefreshLocksFromDifferentNamespaces() throws InterruptedException {
        RemoteLockService lockService1 = getLockService(CLIENT_1);
        RemoteLockService lockService2 = getLockService(CLIENT_2);

        LockRefreshToken token = lockService1.lock(LOCK_CLIENT_NAME, LockRequest.builder(LOCK_MAP)
                .doNotBlock()
                .build());

        assertThat(token).isNotNull();
        assertThat(lockService1.refreshLockRefreshTokens(ImmutableList.of(token))).isNotEmpty();
        assertThat(lockService2.refreshLockRefreshTokens(ImmutableList.of(token))).isEmpty();

        lockService1.unlock(token);
    }

    @Test
    public void timestampServiceShouldGiveUsIncrementalTimestamps() {
        long timestamp1 = timestampService.getFreshTimestamp();
        long timestamp2 = timestampService.getFreshTimestamp();

        assertThat(timestamp1).isLessThan(timestamp2);
    }

    @Test
    public void timestampServiceShouldRespectDistinctClientsWhenIssuingTimestamps() {
        TimestampService timestampService1 = getTimestampService(CLIENT_1);
        TimestampService timestampService2 = getTimestampService(CLIENT_2);

        long firstServiceFirstTimestamp = timestampService1.getFreshTimestamp();
        long secondServiceFirstTimestamp = timestampService2.getFreshTimestamp();

        long firstServiceSecondTimestamp = timestampService1.getFreshTimestamp();
        long secondServiceSecondTimestamp = timestampService2.getFreshTimestamp();

        assertEquals(firstServiceFirstTimestamp + 1, firstServiceSecondTimestamp);
        assertEquals(secondServiceFirstTimestamp + 1, secondServiceSecondTimestamp);
    }

    @Test
    public void timestampServiceRespectsTimestampManagementService() {
        long currentTimestampIncrementedByOneMillion = timestampService.getFreshTimestamp() + ONE_MILLION;
        timestampManagementService.fastForwardTimestamp(currentTimestampIncrementedByOneMillion);
        assertThat(timestampService.getFreshTimestamp()).isGreaterThan(currentTimestampIncrementedByOneMillion);
    }

    @Test
    public void timestampManagementServiceRespectsTimestampService() {
        long currentTimestampIncrementedByOneMillion = timestampService.getFreshTimestamp() + ONE_MILLION;
        timestampManagementService.fastForwardTimestamp(currentTimestampIncrementedByOneMillion);
        getFortyTwoFreshTimestamps(timestampService);
        timestampManagementService.fastForwardTimestamp(currentTimestampIncrementedByOneMillion + 1);
        assertThat(timestampService.getFreshTimestamp())
                .isGreaterThan(currentTimestampIncrementedByOneMillion + FORTY_TWO);
    }

    private static void getFortyTwoFreshTimestamps(TimestampService timestampService) {
        for (int i = 0; i < FORTY_TWO; i++) {
            timestampService.getFreshTimestamp();
        }
    }

    @Test
    public void fastForwardRespectsDistinctClients() {
        TimestampManagementService anotherClientTimestampManagementService = getTimestampManagementService(CLIENT_2);

        long currentTimestamp = timestampService.getFreshTimestamp();
        anotherClientTimestampManagementService.fastForwardTimestamp(currentTimestamp + ONE_MILLION);
        assertEquals(currentTimestamp + 1, timestampService.getFreshTimestamp());
    }

    @Test
    public void fastForwardToThePastDoesNothing() {
        long currentTimestamp = timestampService.getFreshTimestamp();
        long currentTimestampIncrementedByOneMillion = currentTimestamp + ONE_MILLION;
        long currentTimestampIncrementedByTwoMillion = currentTimestamp + TWO_MILLION;

        timestampManagementService.fastForwardTimestamp(currentTimestampIncrementedByTwoMillion);
        timestampManagementService.fastForwardTimestamp(currentTimestampIncrementedByOneMillion);
        assertThat(timestampService.getFreshTimestamp()).isGreaterThan(currentTimestampIncrementedByTwoMillion);
    }

    @Test
    public void returnsNotFoundOnQueryingNonexistentClient() {
        RemoteLockService lockService = getLockService(NONEXISTENT_CLIENT);
        assertThatThrownBy(lockService::currentTimeMillis)
                .hasMessageContaining(NOT_FOUND_CODE);
    }

    @Test
    public void returnsNotFoundOnQueryingClientWithInvalidName() {
        TimestampService invalidTimestampService = getTimestampService(INVALID_CLIENT);
        assertThatThrownBy(invalidTimestampService::getFreshTimestamp)
                .hasMessageContaining(NOT_FOUND_CODE);
    }

    @Test
    public void supportsClientNamesMatchingPaxosRoles() throws InterruptedException {
        getTimestampService("learner").getFreshTimestamp();
        getTimestampService("acceptor").getFreshTimestamp();
    }

    private static RemoteLockService getLockService(String client) {
        return getProxyForService(client, RemoteLockService.class);
    }

    private static TimestampService getTimestampService(String client) {
        return getProxyForService(client, TimestampService.class);
    }

    private static TimestampManagementService getTimestampManagementService(String client) {
        return getProxyForService(client, TimestampManagementService.class);
    }

    private static <T> T getProxyForService(String client, Class<T> clazz) {
        return AtlasDbHttpClients.createProxy(
                NO_SSL,
                String.format("http://localhost:%d/%s", TIMELOCK_SERVER_HOLDER.getTimelockPort(), client),
                clazz);
    }
}
