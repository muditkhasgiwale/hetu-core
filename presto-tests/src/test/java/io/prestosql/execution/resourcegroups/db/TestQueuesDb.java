/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.execution.resourcegroups.db;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.prestosql.Session;
import io.prestosql.dispatcher.DispatchManager;
import io.prestosql.execution.QueryManager;
import io.prestosql.execution.resourcegroups.InternalResourceGroupManager;
import io.prestosql.plugin.resourcegroups.db.DbResourceGroupConfigurationManager;
import io.prestosql.plugin.resourcegroups.db.H2ResourceGroupsDao;
import io.prestosql.server.BasicQueryInfo;
import io.prestosql.server.ResourceGroupInfo;
import io.prestosql.spi.QueryId;
import io.prestosql.spi.resourcegroups.ResourceGroupId;
import io.prestosql.testing.MaterializedResult;
import io.prestosql.tests.DistributedQueryRunner;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static io.airlift.testing.Assertions.assertContains;
import static io.prestosql.SystemSessionProperties.QUERY_MAX_EXECUTION_TIME;
import static io.prestosql.execution.QueryState.FAILED;
import static io.prestosql.execution.QueryState.FINISHED;
import static io.prestosql.execution.QueryState.QUEUED;
import static io.prestosql.execution.QueryState.RUNNING;
import static io.prestosql.execution.TestQueryRunnerUtil.cancelQuery;
import static io.prestosql.execution.TestQueryRunnerUtil.createQuery;
import static io.prestosql.execution.TestQueryRunnerUtil.waitForQueryState;
import static io.prestosql.execution.TestQueues.createResourceGroupId;
import static io.prestosql.execution.resourcegroups.db.H2TestUtil.TEST_ENVIRONMENT;
import static io.prestosql.execution.resourcegroups.db.H2TestUtil.adhocSession;
import static io.prestosql.execution.resourcegroups.db.H2TestUtil.createQueryRunner;
import static io.prestosql.execution.resourcegroups.db.H2TestUtil.dashboardSession;
import static io.prestosql.execution.resourcegroups.db.H2TestUtil.getDao;
import static io.prestosql.execution.resourcegroups.db.H2TestUtil.getDbConfigUrl;
import static io.prestosql.execution.resourcegroups.db.H2TestUtil.getSelectors;
import static io.prestosql.execution.resourcegroups.db.H2TestUtil.rejectingSession;
import static io.prestosql.execution.resourcegroups.db.H2TestUtil.waitForCompleteQueryCount;
import static io.prestosql.execution.resourcegroups.db.H2TestUtil.waitForRunningQueryCount;
import static io.prestosql.spi.StandardErrorCode.EXCEEDED_TIME_LIMIT;
import static io.prestosql.spi.StandardErrorCode.INVALID_RESOURCE_GROUP;
import static io.prestosql.spi.StandardErrorCode.QUERY_QUEUE_FULL;
import static io.prestosql.spi.StandardErrorCode.QUERY_REJECTED;
import static io.prestosql.testing.TestingSession.testSessionBuilder;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

// run single threaded to avoid creating multiple query runners at once
@Test(singleThreaded = true)
public class TestQueuesDb
{
    // Copy of TestQueues with tests for db reconfiguration of resource groups
    private static final String LONG_LASTING_QUERY = "SELECT COUNT(*) FROM lineitem";
    private DistributedQueryRunner queryRunner;
    private H2ResourceGroupsDao dao;

    @BeforeMethod
    public void setup()
            throws Exception
    {
        String dbConfigUrl = getDbConfigUrl();
        dao = getDao(dbConfigUrl);
        queryRunner = createQueryRunner(dbConfigUrl, dao);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown()
    {
        queryRunner.close();
        queryRunner = null;
    }

    @Test(timeOut = 60_000)
    public void testRunningQuery()
            throws Exception
    {
        queryRunner.execute("SELECT COUNT(*), clerk FROM orders GROUP BY clerk");
        while (true) {
            ResourceGroupInfo global = queryRunner.getCoordinator().getResourceGroupManager().get().getResourceGroupInfo(new ResourceGroupId(new ResourceGroupId("global"), "bi-user"));
            if (global.getSoftMemoryLimit().toBytes() > 0) {
                break;
            }
            TimeUnit.SECONDS.sleep(2);
        }
    }

    @Test(timeOut = 60_000)
    public void testBasic()
            throws Exception
    {
        // submit first "dashboard" query
        QueryId firstDashboardQuery = createQuery(queryRunner, dashboardSession(), LONG_LASTING_QUERY);
        // wait for the first "dashboard" query to start
        waitForQueryState(queryRunner, firstDashboardQuery, RUNNING);
        waitForRunningQueryCount(queryRunner, 1);
        // submit second "dashboard" query
        QueryId secondDashboardQuery = createQuery(queryRunner, dashboardSession(), LONG_LASTING_QUERY);
        MILLISECONDS.sleep(2000);
        // wait for the second "dashboard" query to be queued ("dashboard.${USER}" queue strategy only allows one "dashboard" query to be accepted for execution)
        waitForQueryState(queryRunner, secondDashboardQuery, QUEUED);
        waitForRunningQueryCount(queryRunner, 1);
        // Update db to allow for 1 more running query in dashboard resource group
        // Hetu: add parameters softReservedMemory and hardReservedConcurrency
        dao.updateResourceGroup(3, "user-${USER}", "1MB", "10%", 3, 4, 4, 4, null, null, null, null, null, "RECENT_QUERIES", 1L, TEST_ENVIRONMENT);
        dao.updateResourceGroup(5, "dashboard-${USER}", "1MB", "10%", 1, 2, 2, 2, null, null, null, null, null, "RECENT_QUERIES", 3L, TEST_ENVIRONMENT);
        waitForQueryState(queryRunner, secondDashboardQuery, RUNNING);
        QueryId thirdDashboardQuery = createQuery(queryRunner, dashboardSession(), LONG_LASTING_QUERY);
        waitForQueryState(queryRunner, thirdDashboardQuery, QUEUED);
        waitForRunningQueryCount(queryRunner, 2);
        // submit first non "dashboard" query
        QueryId firstNonDashboardQuery = createQuery(queryRunner, adhocSession(), LONG_LASTING_QUERY);
        // wait for the first non "dashboard" query to start
        waitForQueryState(queryRunner, firstNonDashboardQuery, RUNNING);
        waitForRunningQueryCount(queryRunner, 3);
        // submit second non "dashboard" query
        QueryId secondNonDashboardQuery = createQuery(queryRunner, adhocSession(), LONG_LASTING_QUERY);
        // wait for the second non "dashboard" query to start
        waitForQueryState(queryRunner, secondNonDashboardQuery, RUNNING);
        waitForRunningQueryCount(queryRunner, 4);
        // cancel first "dashboard" query, the second "dashboard" query and second non "dashboard" query should start running
        cancelQuery(queryRunner, firstDashboardQuery);
        waitForQueryState(queryRunner, firstDashboardQuery, FAILED);
        waitForQueryState(queryRunner, thirdDashboardQuery, RUNNING);
        waitForRunningQueryCount(queryRunner, 4);
        waitForCompleteQueryCount(queryRunner, 1);
    }

    @Test(timeOut = 60_000)
    public void testTwoQueriesAtSameTime()
            throws Exception
    {
        QueryId firstDashboardQuery = createQuery(queryRunner, dashboardSession(), LONG_LASTING_QUERY);
        QueryId secondDashboardQuery = createQuery(queryRunner, dashboardSession(), LONG_LASTING_QUERY);
        waitForQueryState(queryRunner, firstDashboardQuery, RUNNING);
        waitForQueryState(queryRunner, secondDashboardQuery, QUEUED);
    }

    @Test(timeOut = 90_000)
    public void testTooManyQueries()
            throws Exception
    {
        QueryId firstDashboardQuery = createQuery(queryRunner, dashboardSession(), LONG_LASTING_QUERY);
        waitForQueryState(queryRunner, firstDashboardQuery, RUNNING);

        QueryId secondDashboardQuery = createQuery(queryRunner, dashboardSession(), LONG_LASTING_QUERY);
        waitForQueryState(queryRunner, secondDashboardQuery, QUEUED);

        QueryId thirdDashboardQuery = createQuery(queryRunner, dashboardSession(), LONG_LASTING_QUERY);
        waitForQueryState(queryRunner, thirdDashboardQuery, FAILED);

        // Allow one more query to run and resubmit third query
        // Hetu: add parameters softReservedMemory and hardReservedConcurrency
        dao.updateResourceGroup(3, "user-${USER}", "1MB", "10%", 3, 4, 4, 4, null, null, null, null, null, "RECENT_QUERIES", 1L, TEST_ENVIRONMENT);
        dao.updateResourceGroup(5, "dashboard-${USER}", "1MB", "10%", 1, 2, 2, 2, null, null, null, null, null, "RECENT_QUERIES", 3L, TEST_ENVIRONMENT);

        InternalResourceGroupManager<?> manager = queryRunner.getCoordinator().getResourceGroupManager().get();
        DbResourceGroupConfigurationManager dbConfigurationManager = (DbResourceGroupConfigurationManager) manager.getConfigurationManager();

        // Trigger reload to make the test more deterministic
        dbConfigurationManager.load();
        waitForQueryState(queryRunner, secondDashboardQuery, RUNNING);
        thirdDashboardQuery = createQuery(queryRunner, dashboardSession(), LONG_LASTING_QUERY);
        waitForQueryState(queryRunner, thirdDashboardQuery, QUEUED);

        // Lower running queries in dashboard resource groups and reload the config
        dao.updateResourceGroup(5, "dashboard-${USER}", "1MB", "10%", 1, 1, 1, 1, null, null, null, null, null, "RECENT_QUERIES", 3L, TEST_ENVIRONMENT);
        dbConfigurationManager.load();

        // Cancel query and verify that third query is still queued
        cancelQuery(queryRunner, firstDashboardQuery);
        waitForQueryState(queryRunner, firstDashboardQuery, FAILED);
        MILLISECONDS.sleep(2000);
        waitForQueryState(queryRunner, thirdDashboardQuery, QUEUED);
    }

    @Test(timeOut = 60_000)
    public void testRejection()
            throws Exception
    {
        InternalResourceGroupManager<?> manager = queryRunner.getCoordinator().getResourceGroupManager().get();
        DbResourceGroupConfigurationManager dbConfigurationManager = (DbResourceGroupConfigurationManager) manager.getConfigurationManager();
        // Verify the query cannot be submitted
        QueryId queryId = createQuery(queryRunner, rejectingSession(), LONG_LASTING_QUERY);
        waitForQueryState(queryRunner, queryId, FAILED);
        DispatchManager dispatchManager = queryRunner.getCoordinator().getDispatchManager();
        assertEquals(dispatchManager.getQueryInfo(queryId).getErrorCode(), QUERY_REJECTED.toErrorCode());
        int selectorCount = getSelectors(queryRunner).size();
        dao.insertSelector(4, 100_000, "user.*", "(?i).*reject.*", null, null, null);
        dbConfigurationManager.load();
        assertEquals(getSelectors(queryRunner).size(), selectorCount + 1);
        // Verify the query can be submitted
        queryId = createQuery(queryRunner, rejectingSession(), LONG_LASTING_QUERY);
        waitForQueryState(queryRunner, queryId, RUNNING);
        dao.deleteSelector(4, "user.*", "(?i).*reject.*", null);
        dbConfigurationManager.load();
        // Verify the query cannot be submitted
        queryId = createQuery(queryRunner, rejectingSession(), LONG_LASTING_QUERY);
        waitForQueryState(queryRunner, queryId, FAILED);
    }

    @Test(timeOut = 60_000)
    public void testQuerySystemTableResourceGroup()
            throws Exception
    {
        QueryId firstQuery = createQuery(queryRunner, dashboardSession(), LONG_LASTING_QUERY);
        waitForQueryState(queryRunner, firstQuery, RUNNING);
        MaterializedResult result = queryRunner.execute("SELECT resource_group_id FROM system.runtime.queries WHERE source = 'dashboard'");
        assertEquals(result.getOnlyValue(), ImmutableList.of("global", "user-user", "dashboard-user"));
    }

    @Test(timeOut = 60_000)
    public void testSelectorPriority()
            throws Exception
    {
        InternalResourceGroupManager<?> manager = queryRunner.getCoordinator().getResourceGroupManager().get();
        QueryManager queryManager = queryRunner.getCoordinator().getQueryManager();
        DbResourceGroupConfigurationManager dbConfigurationManager = (DbResourceGroupConfigurationManager) manager.getConfigurationManager();

        QueryId firstQuery = createQuery(queryRunner, dashboardSession(), LONG_LASTING_QUERY);
        waitForQueryState(queryRunner, firstQuery, RUNNING);

        Optional<ResourceGroupId> resourceGroup = queryManager.getFullQueryInfo(firstQuery).getResourceGroupId();
        assertTrue(resourceGroup.isPresent());
        assertEquals(resourceGroup.get().toString(), "global.user-user.dashboard-user");

        // create a new resource group that rejects all queries submitted to it
        // Hetu: add parameters softReservedMemory and hardReservedConcurrency
        dao.insertResourceGroup(8, "reject-all-queries", "1MB", "1MB", 0, 0, 0, 0, null, null, null, null, null, "RECENT_QUERIES", 3L, TEST_ENVIRONMENT);

        // add a new selector that has a higher priority than the existing dashboard selector and that routes queries to the "reject-all-queries" resource group
        dao.insertSelector(8, 200, "user.*", "(?i).*dashboard.*", null, null, null);

        // reload the configuration
        dbConfigurationManager.load();

        QueryId secondQuery = createQuery(queryRunner, dashboardSession(), LONG_LASTING_QUERY);
        waitForQueryState(queryRunner, secondQuery, FAILED);

        DispatchManager dispatchManager = queryRunner.getCoordinator().getDispatchManager();
        BasicQueryInfo basicQueryInfo = dispatchManager.getQueryInfo(secondQuery);
        assertEquals(basicQueryInfo.getErrorCode(), QUERY_QUEUE_FULL.toErrorCode());
    }

    @Test(timeOut = 60_000)
    public void testQueryExecutionTimeLimit()
            throws Exception
    {
        QueryManager queryManager = queryRunner.getCoordinator().getQueryManager();
        InternalResourceGroupManager<?> manager = queryRunner.getCoordinator().getResourceGroupManager().get();
        DbResourceGroupConfigurationManager dbConfigurationManager = (DbResourceGroupConfigurationManager) manager.getConfigurationManager();
        QueryId firstQuery = createQuery(
                queryRunner,
                testSessionBuilder()
                        .setCatalog("tpch")
                        .setSchema("sf100000")
                        .setSource("dashboard")
                        .setSystemProperty(QUERY_MAX_EXECUTION_TIME, "1ms")
                        .build(),
                LONG_LASTING_QUERY);
        waitForQueryState(queryRunner, firstQuery, FAILED);
        assertEquals(queryManager.getFullQueryInfo(firstQuery).getErrorCode(), EXCEEDED_TIME_LIMIT.toErrorCode());
        assertContains(queryManager.getFullQueryInfo(firstQuery).getFailureInfo().getMessage(), "Query exceeded the maximum execution time limit of 1.00ms");
        // set max running queries to 0 for the dashboard resource group so that new queries get queued immediately
        // Hetu: add parameters softReservedMemory and hardReservedConcurrency
        dao.updateResourceGroup(5, "dashboard-${USER}", "1MB", "1MB", 1, null, 0, 0, null, null, null, null, null, "RECENT_QUERIES", 3L, TEST_ENVIRONMENT);
        dbConfigurationManager.load();
        QueryId secondQuery = createQuery(
                queryRunner,
                testSessionBuilder()
                        .setCatalog("tpch")
                        .setSchema("sf100000")
                        .setSource("dashboard")
                        .setSystemProperty(QUERY_MAX_EXECUTION_TIME, "1ms")
                        .build(),
                LONG_LASTING_QUERY);
        //this query should immediately get queued
        waitForQueryState(queryRunner, secondQuery, QUEUED);
        // after a 5s wait this query should still be QUEUED, not FAILED as the max execution time should be enforced after the query starts running
        Thread.sleep(5_000);
        DispatchManager dispatchManager = queryRunner.getCoordinator().getDispatchManager();
        assertEquals(dispatchManager.getQueryInfo(secondQuery).getState(), QUEUED);
        // reconfigure the resource group to run the second query
        // Hetu: add parameters softReservedMemory and hardReservedConcurrency
        dao.updateResourceGroup(5, "dashboard-${USER}", "1MB", "1MB", 1, null, 1, 0, null, null, null, null, null, "RECENT_QUERIES", 3L, TEST_ENVIRONMENT);
        dbConfigurationManager.load();
        // cancel the first one and let the second one start
        dispatchManager.cancelQuery(firstQuery);
        // wait until the second one is FAILED
        waitForQueryState(queryRunner, secondQuery, FAILED);
    }

    @Test
    public void testQueryTypeBasedSelection()
            throws InterruptedException
    {
        Session session = testSessionBuilder()
                .setCatalog("tpch")
                .setSchema("sf100000")
                .build();
        QueryId queryId = createQuery(queryRunner, session, "EXPLAIN " + LONG_LASTING_QUERY);
        waitForQueryState(queryRunner, queryId, ImmutableSet.of(RUNNING, FINISHED));
        Optional<ResourceGroupId> resourceGroupId = queryRunner.getCoordinator().getQueryManager().getFullQueryInfo(queryId).getResourceGroupId();
        assertTrue(resourceGroupId.isPresent(), "Query should have a resource group");
        assertEquals(resourceGroupId.get(), createResourceGroupId("explain"));
    }

    @Test
    public void testClientTagsBasedSelection()
            throws InterruptedException
    {
        assertResourceGroupWithClientTags(ImmutableSet.of("tag1"), createResourceGroupId("global", "bi-user"));
        assertResourceGroupWithClientTags(ImmutableSet.of("tag1", "tag2"), createResourceGroupId("global", "user-user", "adhoc-user"));
    }

    @Test
    public void testNonLeafGroup()
            throws Exception
    {
        Session session = testSessionBuilder()
                .setCatalog("tpch")
                .setSchema("sf100000")
                .setSource("non-leaf")
                .build();
        QueryManager queryManager = queryRunner.getCoordinator().getQueryManager();
        InternalResourceGroupManager<?> manager = queryRunner.getCoordinator().getResourceGroupManager().get();
        DbResourceGroupConfigurationManager dbConfigurationManager = (DbResourceGroupConfigurationManager) manager.getConfigurationManager();
        int originalSize = getSelectors(queryRunner).size();
        // Add a selector for a non leaf group
        dao.insertSelector(3, 100, "user.*", "(?i).*non-leaf.*", null, null, null);
        dbConfigurationManager.load();
        while (getSelectors(queryRunner).size() != originalSize + 1) {
            MILLISECONDS.sleep(500);
        }
        // Submit query with side effect of creating resource groups
        QueryId firstDashboardQuery = createQuery(queryRunner, dashboardSession(), LONG_LASTING_QUERY);
        waitForQueryState(queryRunner, firstDashboardQuery, RUNNING);
        cancelQuery(queryRunner, firstDashboardQuery);
        waitForQueryState(queryRunner, firstDashboardQuery, FAILED);
        // Submit a query to a non-leaf resource group
        QueryId invalidResourceGroupQuery = createQuery(queryRunner, session, LONG_LASTING_QUERY);
        waitForQueryState(queryRunner, invalidResourceGroupQuery, FAILED);
        assertEquals(queryRunner.getCoordinator().getDispatchManager().getQueryInfo(invalidResourceGroupQuery).getErrorCode(), INVALID_RESOURCE_GROUP.toErrorCode());
    }

    private void assertResourceGroupWithClientTags(Set<String> clientTags, ResourceGroupId expectedResourceGroup)
            throws InterruptedException
    {
        Session session = testSessionBuilder()
                .setCatalog("tpch")
                .setSchema("sf100000")
                .setSource("client_tags")
                .setClientTags(clientTags)
                .build();
        QueryId queryId = createQuery(queryRunner, session, LONG_LASTING_QUERY);
        waitForQueryState(queryRunner, queryId, ImmutableSet.of(RUNNING, FINISHED));
        Optional<ResourceGroupId> resourceGroupId = queryRunner.getCoordinator().getQueryManager().getFullQueryInfo(queryId).getResourceGroupId();
        assertTrue(resourceGroupId.isPresent(), "Query should have a resource group");
        assertEquals(resourceGroupId.get(), expectedResourceGroup, format("Expected: '%s' resource group, found: %s", expectedResourceGroup, resourceGroupId.get()));
    }
}
