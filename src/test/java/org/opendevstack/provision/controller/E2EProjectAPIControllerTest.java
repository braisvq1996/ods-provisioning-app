/*
 * Copyright 2017-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.opendevstack.provision.controller;

import static java.lang.String.format;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;
import static org.opendevstack.provision.util.RestClientCallArgumentMatcher.matchesClientCall;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.OngoingStubbing;
import org.mockito.verification.VerificationMode;
import org.opendevstack.provision.SpringBoot;
import org.opendevstack.provision.model.ExecutionJob;
import org.opendevstack.provision.model.OpenProjectData;
import org.opendevstack.provision.model.bitbucket.BitbucketProject;
import org.opendevstack.provision.model.bitbucket.BitbucketProjectData;
import org.opendevstack.provision.model.bitbucket.Repository;
import org.opendevstack.provision.model.bitbucket.RepositoryData;
import org.opendevstack.provision.model.confluence.Blueprint;
import org.opendevstack.provision.model.confluence.JiraServer;
import org.opendevstack.provision.model.confluence.Space;
import org.opendevstack.provision.model.confluence.SpaceData;
import org.opendevstack.provision.model.jenkins.Execution;
import org.opendevstack.provision.model.jira.LeanJiraProject;
import org.opendevstack.provision.model.jira.PermissionScheme;
import org.opendevstack.provision.model.jira.PermissionSchemeResponse;
import org.opendevstack.provision.model.webhookproxy.CreateProjectResponse;
import org.opendevstack.provision.services.BitbucketAdapter;
import org.opendevstack.provision.services.ConfluenceAdapter;
import org.opendevstack.provision.services.CrowdProjectIdentityMgmtAdapter;
import org.opendevstack.provision.services.JenkinsPipelineAdapter;
import org.opendevstack.provision.services.JiraAdapter;
import org.opendevstack.provision.services.MailAdapter;
import org.opendevstack.provision.storage.LocalStorage;
import org.opendevstack.provision.util.CreateProjectResponseUtil;
import org.opendevstack.provision.util.RestClientCallArgumentMatcher;
import org.opendevstack.provision.util.rest.RestClient;
import org.opendevstack.provision.util.rest.RestClientMockHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.result.MockMvcResultHandlers;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * End to end testcase with real result data - only mock is the RestClient - to feed the json
 *
 * @author utschig
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = SpringBoot.class)
@DirtiesContext
public class E2EProjectAPIControllerTest {
  private static Logger e2eLogger = LoggerFactory.getLogger(E2EProjectAPIControllerTest.class);

  @InjectMocks @Autowired private JiraAdapter realJiraAdapter;

  @InjectMocks @Autowired private ConfluenceAdapter realConfluenceAdapter;

  @InjectMocks @Autowired private BitbucketAdapter realBitbucketAdapter;

  @InjectMocks @Autowired private JenkinsPipelineAdapter realRundeckAdapter;

  @Mock RestClient restClient;
  RestClientMockHelper mockHelper;

  @Mock CrowdProjectIdentityMgmtAdapter idmgtAdapter;

  @InjectMocks @Autowired private ProjectApiController apiController;

  @Autowired private LocalStorage realStorageAdapter;

  @Autowired private MailAdapter realMailAdapter;

  @Value("${idmanager.group.opendevstack-users}")
  private String userGroup;

  @Value("${idmanager.group.opendevstack-administrators}")
  private String adminGroup;

  private MockMvc mockMvc;

  // directory containing all the e2e test data
  private static File testDataDir = new File("src/test/resources/e2e/");

  // results directory
  private static File resultsDir = new File(testDataDir, "results");

  // do NOT delete on cleanup
  private static List<String> excludeFromCleanup =
      Arrays.asList("20190101171023-LEGPROJ.txt", "20190101171024-CRACKED.txt");
  private MockHttpSession mocksession;

  @Before
  public void setUp() throws Exception {
    cleanUp();
    mocksession = new MockHttpSession();
    MockitoAnnotations.initMocks(this);
    mockMvc = MockMvcBuilders.standaloneSetup(apiController).build();
    mockHelper = new RestClientMockHelper(restClient);
    // setup storage against test directory
    realStorageAdapter.setLocalStoragePath(resultsDir.getPath());

    // disable mail magic
    realMailAdapter.isMailEnabled = false;
  }

  @AfterClass
  public static void cleanUp() throws Exception {
    for (File fresult : resultsDir.listFiles()) {
      if (fresult.isDirectory() || excludeFromCleanup.contains(fresult.getName())) {
        continue;
      }
      e2eLogger.debug("Deleting file {} result: {}", fresult.getName(), fresult.delete());
    }
  }

  /** Test positive - e2e new project - no quickstarters */
  @Test
  public void testProvisionNewSimpleProjectE2E() throws Exception {
    testProvisionNewSimpleProjectInternal(false, false);
  }

  /**
   * Test negative - e2e new project - no quickstarters, rollback any external changes - bugtracker,
   * scm,...
   */
  @Test
  public void testProvisionNewSimpleProjectE2EFail() throws Exception {
    cleanUp();
    testProvisionNewSimpleProjectInternal(true, false);
  }

  /**
   * Test negative - e2e new project, with perm set - no quickstarters, rollback any external
   * changes - bugtracker, scm,... and also permission set
   */
  @Test
  public void testProvisionNewSimplePermsetProjectE2EFail() throws Exception {
    cleanUp();
    testProvisionNewSimpleProjectInternal(true, true);
  }

  /** Test negative - e2e new project - no quickstarters, but NO cleanup allowed :) */
  @Test
  public void testProvisionNewSimpleProjectE2EFailCleanupNotAllowed() throws Exception {
    cleanUp();
    apiController.cleanupAllowed = false;
    testProvisionNewSimpleProjectInternal(true, false);
  }

  public void testProvisionNewSimpleProjectInternal(boolean fail, boolean specialPermissionSet)
      throws Exception {
    // read the request
    OpenProjectData data = readTestData("ods-create-project-request", OpenProjectData.class);

    data.specialPermissionSet = specialPermissionSet;

    // jira server create project response
    LeanJiraProject jiraProject =
        readTestData("jira-create-project-response", LeanJiraProject.class);

    mockHelper
        .mockExecute(
            matchesClientCall()
                .url(containsString(realJiraAdapter.getAdapterApiUri() + "/project"))
                .method(HttpMethod.POST))
        .thenReturn(jiraProject);

    // jira server find & create permission scheme
    PermissionSchemeResponse jiraProjectPermSet =
        readTestData("jira-get-project-permissionsscheme", PermissionSchemeResponse.class);

    mockHelper
        .mockExecute(
            matchesClientCall().url(containsString("/permissionscheme")).method(HttpMethod.GET))
        .thenReturn(jiraProjectPermSet);

    PermissionScheme jiraProjectPermSetCreate =
        readTestData("jira-get-project-permissionsscheme", PermissionScheme.class);

    mockHelper
        .mockExecute(
            matchesClientCall().url(containsString("/permissionscheme")).method(HttpMethod.POST))
        .thenReturn(jiraProjectPermSetCreate);

    // get confluence blueprints
    List<Blueprint> blList =
        readTestDataTypeRef(
            "confluence-get-blueprints-response", new TypeReference<List<Blueprint>>() {});

    mockHelper
        .mockExecute(
            matchesClientCall().url(containsString("dialog/web-items")).method(HttpMethod.GET))
        .thenReturn(blList);

    // get jira servers for confluence space
    List<JiraServer> jiraservers =
        readTestDataTypeRef(
            "confluence-get-jira-servers-response", new TypeReference<List<JiraServer>>() {});

    mockHelper
        .mockExecute(
            matchesClientCall()
                .url(containsString("jiraanywhere/1.0/servers"))
                .method(HttpMethod.GET))
        .thenReturn(jiraservers);

    SpaceData confluenceSpace = readTestData("confluence-create-space-response", SpaceData.class);

    mockHelper
        .mockExecute(
            matchesClientCall()
                .url(containsString("space-blueprint/create-space"))
                .bodyMatches(instanceOf(Space.class))
                .method(HttpMethod.POST))
        .thenReturn(confluenceSpace);

    // bitbucket main project creation
    BitbucketProjectData bitbucketProjectData =
        readTestData("bitbucket-create-project-response", BitbucketProjectData.class);

    mockHelper
        .mockExecute(
            matchesClientCall()
                .url(containsString(realBitbucketAdapter.getAdapterApiUri()))
                .bodyMatches(instanceOf(BitbucketProject.class))
                .method(HttpMethod.POST)
                .returnType(BitbucketProjectData.class))
        .thenReturn(bitbucketProjectData);

    // bitbucket aus repo creation - oc-config
    RepositoryData bitbucketRepositoryDataOCConfig =
        readTestData("bitbucket-create-repo-occonfig-response", RepositoryData.class);
    Repository occonfigRepo = new Repository();
    occonfigRepo.setName(bitbucketRepositoryDataOCConfig.getName());
    occonfigRepo.setUserGroup(userGroup);
    occonfigRepo.setAdminGroup(adminGroup);

    // bitbucket aux repo creation - design repo
    RepositoryData bitbucketRepositoryDataDesign =
        readTestData("bitbucket-create-repo-design-response", RepositoryData.class);
    Repository designRepo = new Repository();
    designRepo.setName(bitbucketRepositoryDataDesign.getName());
    designRepo.setUserGroup(userGroup);
    designRepo.setAdminGroup(adminGroup);

    mockHelper
        .mockExecute(
            matchesClientCall()
                .url(containsString(realBitbucketAdapter.getAdapterApiUri() + "/TESTP/repos"))
                .method(HttpMethod.POST)
                .returnType(RepositoryData.class))
        .thenReturn(bitbucketRepositoryDataOCConfig, bitbucketRepositoryDataDesign);

    // verify jira components for new repos NOT created (just 2 auxiliaries)
    mockHelper.verifyExecute(
        matchesClientCall()
            .url(containsString(realJiraAdapter.getAdapterApiUri() + "/component"))
            .method(HttpMethod.POST),
        times(0));

    CreateProjectResponse configuredResponse =
        CreateProjectResponseUtil.buildDummyCreateProjectResponse("demo-cd", "build-config", 1);
    // will cause cleanup
    if (fail) {
      mockHelper
          .mockExecute(
              matchesClientCall()
                  .url(
                      containsString(
                          createJenkinsJobPath(
                              "create-projects/Jenkinsfile", "ods-corejob-create-projects-testp")))
                  // .url(containsString("/run"))
                  .bodyMatches(instanceOf(Execution.class))
                  .method(HttpMethod.POST))
          .thenThrow(new IOException("Rundeck TestFail"));
    } else {
      mockHelper
          .mockExecute(
              matchesClientCall()
                  .url(
                      containsString(
                          createJenkinsJobPath(
                              "create-projects/Jenkinsfile", "ods-corejob-create-projects-testp")))
                  .bodyMatches(instanceOf(Execution.class))
                  .method(HttpMethod.POST))
          .thenReturn(configuredResponse);
    }

    // create the ODS project
    MvcResult resultProjectCreationResponse =
        mockMvc
            .perform(
                post("/api/v2/project")
                    .content(ProjectApiControllerTest.asJsonString(data))
                    .contentType(MediaType.APPLICATION_JSON)
                    .session(mocksession)
                    .accept(MediaType.APPLICATION_JSON))
            .andDo(MockMvcResultHandlers.print())
            .andReturn();

    if (!fail) {
      assertEquals(
          MockHttpServletResponse.SC_OK, resultProjectCreationResponse.getResponse().getStatus());
    } else {
      assertEquals(
          MockHttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          resultProjectCreationResponse.getResponse().getStatus());

      assertTrue(
          resultProjectCreationResponse
              .getResponse()
              .getContentAsString()
              .contains(
                  "An error occured while creating project [TESTP], reason [Rundeck TestFail] - but all cleaned up!"));

      // no cleanup happening - so no delete calls
      if (!apiController.cleanupAllowed) {
        // 5 delete calls, jira / confluence / bitbucket project and two repos
        //        Mockito.verify(mockOldRestClient, times(0))
        //            .callHttp(anyString(), eq(null), anyBoolean(), eq(HttpVerb.DELETE), eq(null));
        mockHelper.verifyExecute(matchesClientCall().method(HttpMethod.DELETE), never());
        return;
      }

      // 5 delete calls, jira / confluence / bitbucket project and two repos, or 6 if permission set
      int overallDeleteCalls = specialPermissionSet ? 6 : 5;
      mockHelper.verifyExecute(
          matchesClientCall().method(HttpMethod.DELETE), times(overallDeleteCalls));

      // delete jira project (and protentially permission set)
      int jiraDeleteCalls = specialPermissionSet ? 2 : 1;
      mockHelper.verifyExecute(
          matchesClientCall()
              .url(containsString(realJiraAdapter.getAdapterApiUri()))
              .method(HttpMethod.DELETE),
          times(jiraDeleteCalls));

      // delete confluence space
      mockHelper.verifyExecute(
          matchesClientCall()
              .url(containsString(realConfluenceAdapter.getAdapterApiUri()))
              .method(HttpMethod.DELETE),
          times(1));

      // delete repos and bitbucket project
      mockHelper.verifyExecute(
          matchesClientCall()
              .url(containsString(realBitbucketAdapter.getAdapterApiUri()))
              .method(HttpMethod.DELETE),
          times(3));
      mockHelper.verifyExecute(
          matchesClientCall()
              .url(
                  allOf(
                      containsString(realBitbucketAdapter.getAdapterApiUri()),
                      containsString("repos")))
              .method(HttpMethod.DELETE),
          times(2));
      mockHelper.verifyExecute(
          matchesClientCall()
              .url(containsString(realConfluenceAdapter.getAdapterApiUri()))
              .method(HttpMethod.DELETE),
          times(1));
      return;
    }

    // get the project thru its key
    MvcResult resultProjectGetResponse =
        mockMvc
            .perform(
                get("/api/v2/project/" + data.projectKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .session(mocksession))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andDo(MockMvcResultHandlers.print())
            .andReturn();

    // verify responses
    assertEquals(
        resultProjectCreationResponse.getResponse().getContentAsString(),
        resultProjectGetResponse.getResponse().getContentAsString());

    OpenProjectData resultProject =
        new ObjectMapper()
            .readValue(
                resultProjectGetResponse.getResponse().getContentAsString(), OpenProjectData.class);

    // verify the execution
    assertEquals(1, resultProject.lastExecutionJobs.size());
    ExecutionJob actualJob = resultProject.lastExecutionJobs.iterator().next();
    assertActualJobMatchesInputParams(actualJob, configuredResponse);

    // verify 2 repos are created
    assertEquals("Repository created", 2, resultProject.repositories.size());
  }

  private void assertActualJobMatchesInputParams(
      ExecutionJob actualJob, CreateProjectResponse configuredResponse) {
    String namespace = configuredResponse.extractNamespace();
    Assertions.assertThat(actualJob.getName())
        .isEqualTo(namespace + "-" + configuredResponse.extractBuildConfigName());

    Assertions.assertThat(actualJob.getUrl())
        .contains(
            format(
                "https://jenkins-%s.192.168.56.101.nip.io/job/%s/job/%s-%s/%s",
                namespace,
                namespace,
                namespace,
                configuredResponse.extractBuildConfigName(),
                configuredResponse.extractBuildNumber()));

    String expectedUrlSuffix =
        configuredResponse.extractBuildConfigName() + "/" + configuredResponse.extractBuildNumber();
    Assertions.assertThat(actualJob.getUrl()).endsWith(expectedUrlSuffix);
  }

  /** Test positive new quickstarter */
  @Test
  public void testQuickstarterProvisionOnNewOpenProject() throws Exception {
    testQuickstarterProvisionOnNewOpenProject(false);
  }

  /** Test positive new quickstarter and delete whole project afterwards */
  @Test
  public void testQuickstarterProvisionOnNewOpenProjectInclDeleteWholeProject() throws Exception {
    OpenProjectData createdProjectIncludingQuickstarters =
        testQuickstarterProvisionOnNewOpenProject(false);

    assertNotNull(createdProjectIncludingQuickstarters);
    assertNotNull(createdProjectIncludingQuickstarters.projectKey);
    assertNotNull(createdProjectIncludingQuickstarters.quickstarters);
    assertEquals(1, createdProjectIncludingQuickstarters.quickstarters.size());

    OpenProjectData toClean = new OpenProjectData();
    toClean.projectKey = createdProjectIncludingQuickstarters.projectKey;
    toClean.quickstarters = createdProjectIncludingQuickstarters.quickstarters;

    CreateProjectResponse configuredResponse =
        CreateProjectResponseUtil.buildDummyCreateProjectResponse("testp", "build-config", 1);
    String jenkinsJobPath =
        createJenkinsJobPath("delete-projects/Jenkinsfile", "ods-corejob-delete-projects-testp");
    mockHelper
        .mockExecute(
            matchesClientCall()
                .url(containsString(jenkinsJobPath))
                .bodyMatches(instanceOf(Execution.class))
                .method(HttpMethod.POST))
        .thenReturn(configuredResponse);
    // delete whole projects (-cd, -dev and -test), calls
    // org.opendevstack.provision.controller.ProjectApiController.deleteProject

    mockMvc
        .perform(
            delete("/api/v2/project/" + toClean.projectKey)
                .contentType(MediaType.APPLICATION_JSON)
                .session(mocksession)
                .accept(MediaType.APPLICATION_JSON))
        .andDo(MockMvcResultHandlers.print())
        .andExpect(MockMvcResultMatchers.status().isOk());
  }

  /** Test positive new quickstarter and delete single component afterwards */
  @Test
  @Ignore("TODO via #296")
  public void testQuickstarterProvisionOnNewOpenProjectInclDeleteSingleComponent()
      throws Exception {
    OpenProjectData createdProjectIncludingQuickstarters =
        testQuickstarterProvisionOnNewOpenProject(false);

    assertNotNull(createdProjectIncludingQuickstarters);
    assertNotNull(createdProjectIncludingQuickstarters.projectKey);
    assertNotNull(createdProjectIncludingQuickstarters.quickstarters);
    assertEquals(1, createdProjectIncludingQuickstarters.quickstarters.size());

    OpenProjectData toClean = new OpenProjectData();
    toClean.projectKey = createdProjectIncludingQuickstarters.projectKey;
    toClean.quickstarters = createdProjectIncludingQuickstarters.quickstarters;

    CreateProjectResponse configuredResponse =
        CreateProjectResponseUtil.buildDummyCreateProjectResponse("testp", "build-config", 1);
    String jenkinsJobPath =
        createJenkinsJobPath("delete-projects/Jenkinsfile", "ods-corejob-delete-projects-testp");
    mockHelper
        .mockExecute(
            matchesClientCall()
                .url(containsString(jenkinsJobPath))
                .bodyMatches(instanceOf(Execution.class))
                .method(HttpMethod.POST))
        .thenReturn(configuredResponse);
    // delete single component (via
    // org.opendevstack.provision.controller.ProjectApiController.deleteComponents)
    mockMvc
        .perform(
            delete("/api/v2/project/")
                .content(ProjectApiControllerTest.asJsonString(toClean))
                .contentType(MediaType.APPLICATION_JSON)
                .session(mocksession)
                .accept(MediaType.APPLICATION_JSON))
        .andDo(MockMvcResultHandlers.print())
        .andExpect(MockMvcResultMatchers.status().isOk());
  }

  /** Test NEGATIVE new quickstarter - rollback ONE created repo */
  @Test
  public void testQuickstarterProvisionOnNewOpenProjectFail() throws Exception {
    testQuickstarterProvisionOnNewOpenProject(true);
  }

  public OpenProjectData testQuickstarterProvisionOnNewOpenProject(boolean fail) throws Exception {
    // read the request
    OpenProjectData dataUpdate =
        readTestData("ods-update-project-python-qs-request", OpenProjectData.class);

    // if project does not exist, create it thru the test
    if (realStorageAdapter.getProject(dataUpdate.projectKey) == null) {
      testProvisionNewSimpleProjectE2E();
    }

    OpenProjectData currentlyStoredProject = realStorageAdapter.getProject(dataUpdate.projectKey);

    assertNull(currentlyStoredProject.quickstarters);

    // bitbucket repo creation for new quickstarter
    RepositoryData bitbucketRepositoryDataQSRepo =
        readTestData("bitbucket-create-repo-python-qs-response", RepositoryData.class);

    Repository qsrepo = new Repository();
    qsrepo.setName(bitbucketRepositoryDataQSRepo.getName());
    qsrepo.setUserGroup(userGroup);
    qsrepo.setAdminGroup(adminGroup);

    mockHelper
        .mockExecute(
            matchesClientCall()
                .url(containsString(realBitbucketAdapter.getAdapterApiUri()))
                .method(HttpMethod.POST))
        .thenReturn(bitbucketRepositoryDataQSRepo);

    CreateProjectResponse configuredCreateProjectResponse =
        CreateProjectResponseUtil.buildDummyCreateProjectResponse("demo-dev", "my-build-config", 2);

    OngoingStubbing<Object> stub =
        mockHelper.mockExecute(
            matchesClientCall()
                .url(
                    containsString(
                        "https://webhook-proxy-testp-cd.192.168.56.101.nip.io/build?trigger_secret="))
                .url(
                    containsString(
                        "&jenkinsfile_path=be-python-flask/Jenkinsfile&component=ods-quickstarter-bePythonFlask-be-python-flask"))
                .bodyMatches(instanceOf(Execution.class))
                .method(HttpMethod.POST));
    if (fail) {
      stub.thenThrow(
          new IOException("Provision via Jenkins fails, because this was requested in test."));
    } else {
      stub.thenReturn(configuredCreateProjectResponse);
    }

    // update the project with the new quickstarter
    MvcResult resultUpdateResponse =
        mockMvc
            .perform(
                put("/api/v2/project")
                    .content(ProjectApiControllerTest.asJsonString(dataUpdate))
                    .contentType(MediaType.APPLICATION_JSON)
                    .session(mocksession)
                    .accept(MediaType.APPLICATION_JSON))
            .andDo(MockMvcResultHandlers.print())
            .andReturn();

    if (fail) {
      assertEquals(
          MockHttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          resultUpdateResponse.getResponse().getStatus());

      // delete repository
      VerificationMode times = times(1);
      mockHelper.verifyExecute(
          matchesClientCall()
              .url(containsString(realBitbucketAdapter.getAdapterApiUri()))
              .method(HttpMethod.DELETE),
          times);

      // verify project(s) are untouched

      mockHelper.verifyExecute(
          matchesClientCall()
              .url(containsString(realJiraAdapter.getAdapterApiUri()))
              .method(HttpMethod.DELETE),
          times(0));

      mockHelper.verifyExecute(
          matchesClientCall()
              .url(containsString(realConfluenceAdapter.getAdapterApiUri()))
              .method(HttpMethod.DELETE),
          times(0));

      // verify component based on repo in jira created
      mockHelper.verifyExecute(
          matchesClientCall()
              .url(containsString(realJiraAdapter.getAdapterApiUri() + "/component"))
              .method(HttpMethod.POST),
          times(1));

      return dataUpdate;
    } else {
      assertEquals(MockHttpServletResponse.SC_OK, resultUpdateResponse.getResponse().getStatus());
    }

    // get the inlined body result
    String resultUpdateData = resultUpdateResponse.getResponse().getContentAsString();
    assertNotNull(resultUpdateData);

    // convert into a project pojo
    OpenProjectData resultProject =
        new ObjectMapper().readValue(resultUpdateData, OpenProjectData.class);

    List<Map<String, String>> createdQuickstarters = resultProject.quickstarters;

    assertNotNull(createdQuickstarters);
    assertEquals(1, createdQuickstarters.size());

    assertEquals(1, resultProject.lastExecutionJobs.size());
    ExecutionJob actualJob = resultProject.lastExecutionJobs.iterator().next();
    assertActualJobMatchesInputParams(actualJob, configuredCreateProjectResponse);

    // return the new fully built project for further use
    return resultProject;
  }

  public void oldVerify(VerificationMode times, RestClientCallArgumentMatcher wantedArgument)
      throws IOException {
    mockHelper.verifyExecute(wantedArgument, times);
  }

  /** Test legacy upgrade e2e */
  @Test
  public void testLegacyProjectUpgradeOnGet() throws Exception {
    MvcResult resultLegacyProjectGetResponse =
        mockMvc
            .perform(
                get("/api/v2/project/LEGPROJ")
                    .accept(MediaType.APPLICATION_JSON)
                    .session(mocksession))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andDo(MockMvcResultHandlers.print())
            .andReturn();

    OpenProjectData resultLegacyProject =
        new ObjectMapper()
            .readValue(
                resultLegacyProjectGetResponse.getResponse().getContentAsString(),
                OpenProjectData.class);

    // verify 4 repos are there - 2 base 2 qs
    assertEquals(4, resultLegacyProject.repositories.size());

    // verify 2 quickstarters are there
    assertEquals(2, resultLegacyProject.quickstarters.size());
  }

  @Test
  public void getProjectQuickStarterDescription() throws Exception {
    mockMvc
        .perform(get("/api/v2/project/LEGPROJ").accept(MediaType.APPLICATION_JSON))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(
            jsonPath(
                "$.quickstarters[0].component_type", is("9992a587-959c-4ceb-8e3f-c1390e40c582")))
        .andExpect(jsonPath("$.quickstarters[0].component_id", is("be-python-flask")))
        .andExpect(
            jsonPath("$.quickstarters[0].component_description", is("Backend - Python/Flask")))
        .andExpect(jsonPath("$.quickstarters[1].component_type", is("bePythonFlask")))
        .andExpect(jsonPath("$.quickstarters[1].component_id", is("logviewer")))
        .andExpect(
            jsonPath("$.quickstarters[1].component_description", is("Backend - Python/Flask")))
        .andDo(MockMvcResultHandlers.print());
  }

  /*
   * internal test helpers
   */

  private <T> T readTestData(String name, Class<T> returnType) throws Exception {
    return new ObjectMapper().readValue(findTestFile(name), returnType);
  }

  private <T> T readTestDataTypeRef(String name, TypeReference<T> returnType) throws Exception {
    return new ObjectMapper().readValue(findTestFile(name), returnType);
  }

  private File findTestFile(String fileName) throws IOException {
    Preconditions.checkNotNull(fileName, "File cannot be null");
    if (!fileName.endsWith(".json")) {
      fileName = fileName + ".json";
    }
    File dataFile = new File(testDataDir, fileName);
    if (!dataFile.exists()) {
      throw new IOException("Cannot find testfile with name:" + dataFile.getName());
    }
    return dataFile;
  }

  private String createJenkinsJobPath(String jenkinsfilePath, String component) {
    return "https://webhook-proxy-prov-cd.192.168.56.101.nip.io/build?trigger_secret=secret101&jenkinsfile_path="
        + jenkinsfilePath
        + "&component="
        + component;
  }
}
