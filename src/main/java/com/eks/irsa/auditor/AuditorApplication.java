package com.eks.irsa.auditor;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1ServiceAccount;
import io.kubernetes.client.openapi.models.V1ServiceAccountList;
import io.kubernetes.client.util.Config;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AttachedPolicy;
import software.amazon.awssdk.services.iam.model.GetPolicyRequest;
import software.amazon.awssdk.services.iam.model.GetPolicyResponse;
import software.amazon.awssdk.services.iam.model.GetPolicyVersionRequest;
import software.amazon.awssdk.services.iam.model.GetPolicyVersionResponse;
import software.amazon.awssdk.services.iam.model.GetRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.GetRolePolicyResponse;
import software.amazon.awssdk.services.iam.model.ListAttachedRolePoliciesRequest;
import software.amazon.awssdk.services.iam.model.ListAttachedRolePoliciesResponse;
import software.amazon.awssdk.services.iam.model.ListRolePoliciesRequest;
import software.amazon.awssdk.services.iam.model.ListRolePoliciesResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SpringBootApplication
public class AuditorApplication implements CommandLineRunner {

	private static final String CONDITION = "Condition";
	private static final String IRSA_AUDITOR = "irsa-auditor";
	private static final String JSON = ".json";
	private static final String IRSA_REPORT_PREFIX = "irsa-report-";
	private static final String APPLICATION_JSON = "application/json";
	private static final String RESOURCE = "Resource";
	private static final String ACTION = "Action";
	private static final String EFFECT = "Effect";
	private static final String STATEMENT = "Statement";
	private static final String UTF_8 = "UTF-8";
	private static final String EKS_AMAZONAWS_COM_ROLE_ARN = "eks.amazonaws.com/role-arn";

	@Value("${AWS_WEB_IDENTITY_TOKEN_FILE}")
	private String awsWebIdentityTokenFile;

	@Value("${AWS_ROLE_ARN}")
	private String awsRoleArn;

	@Value("${AWS_REGION}")
	private String awsRegion;

	public static void main(String[] args) {
		SpringApplication.run(AuditorApplication.class, args);
	}

	@Override
	public void run(String... args) throws Exception {

		if(args.length == 0) {
			System.err.println("Pass S3 bucket name as the argument");
			System.exit(1);
		}

		String bucketName = args[0];
		
		// Initialize Kubernetes client
		ApiClient client = Config.defaultClient();
        Configuration.setDefaultApiClient(client);

		// Get AWS credentials
		Credentials credentials = getAWSCredentials();
		AwsSessionCredentials awsCreds = AwsSessionCredentials.create(
			credentials.accessKeyId(), 
			credentials.secretAccessKey(), 
			credentials.sessionToken());
		StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(awsCreds);
		
		// Initialize IAM client
		Region region = Region.AWS_GLOBAL;
		IamClient iam = IamClient.builder()
			.region(region)
			.credentialsProvider(credentialsProvider)
			.build();

		// Get all service accounts across all namespaces
        CoreV1Api api = new CoreV1Api();
        V1ServiceAccountList list = api.listServiceAccountForAllNamespaces(null, null, null, 
			null, null, null,null, null, 60, null);

		List<IRSAReport> irsaReports = new ArrayList<>();
		for (V1ServiceAccount serviceAccount : list.getItems()) {
            V1ObjectMeta metadata = serviceAccount.getMetadata();
            String role = null;
            if (metadata != null) { 
                Map<String, String> annotationsMap = metadata.getAnnotations();

                if (annotationsMap != null)
                    role = annotationsMap.get(EKS_AMAZONAWS_COM_ROLE_ARN);
                
                if (role != null) {
                    System.out.printf("Service account - %s has IAM role - %s%n", metadata.getName(), role); 
                    String roleName = role.substring(role.lastIndexOf("/") + 1);
                    IRSAReport report = new IRSAReport();	
					report.setRole(roleName);
					report.setServiceAccount(metadata.getName());
					report.setNamespace(metadata.getNamespace());
                    List<Policy> inlinePolicies = getInlinePolicies(iam, roleName);
					List<Policy> attachedPolicies = getAttachedRolePolicies(iam, roleName);
					inlinePolicies.addAll(attachedPolicies);
					report.setPolicies(inlinePolicies);
					irsaReports.add(report);
                }
            } 
        }
		ObjectMapper mapper = new ObjectMapper();
		String jsonString = mapper.writeValueAsString(irsaReports);
		uploadToS3(bucketName, jsonString, credentialsProvider);
	}

	private List<Policy> getInlinePolicies(IamClient iam, String roleName) {
		List<Policy> policies = new ArrayList<>();
        // Inline policies attached to a role
        ListRolePoliciesRequest listRolePoliciesRequest = ListRolePoliciesRequest
			.builder()
			.roleName(roleName)
			.build();

		ListRolePoliciesResponse listRolePoliciesResponse = iam.listRolePolicies(listRolePoliciesRequest);
		if (listRolePoliciesResponse != null && listRolePoliciesResponse.hasPolicyNames()) {
			List<String> policyNames = listRolePoliciesResponse.policyNames();
			policyNames.forEach(policyName -> {
				GetRolePolicyRequest getRolePolicyRequest = GetRolePolicyRequest
					.builder()
					.roleName(roleName)
					.policyName(policyName)
					.build();
				Policy policy = new Policy();
				policy.setName(policyName);
				GetRolePolicyResponse getRolePolicyResponse = iam.getRolePolicy(getRolePolicyRequest);
				
				try {
					String encodedPolicyDocument = getRolePolicyResponse.policyDocument();
					String decodedPolicyDocument = URLDecoder.decode(encodedPolicyDocument,UTF_8);
					List<Statement> statementList = parsePolicyDocument(decodedPolicyDocument);
					policy.setStatements(statementList);
					policies.add(policy);
				} catch (UnsupportedEncodingException e) {
					System.out.println(e);
				}
			});
		}
		return policies;
    }

    private List<Policy> getAttachedRolePolicies (IamClient iam, String roleName) {

		List<Policy> policies = new ArrayList<>();
		// Customer managed and AWS managed policies attached to a role
		ListAttachedRolePoliciesRequest listAttachedRolePoliciesRequest = ListAttachedRolePoliciesRequest
			.builder()
			.roleName(roleName)
			.build();
		
		ListAttachedRolePoliciesResponse listAttachedRolePoliciesResponse = iam.listAttachedRolePolicies(listAttachedRolePoliciesRequest);
		
		if(listAttachedRolePoliciesResponse != null && listAttachedRolePoliciesResponse.hasAttachedPolicies()) {
			List<AttachedPolicy> attachedPolicies = listAttachedRolePoliciesResponse.attachedPolicies();
			if(!attachedPolicies.isEmpty()) {
				attachedPolicies.forEach(attachedPolicy -> {
					String policyArn = attachedPolicy.policyArn();
					
					GetPolicyRequest getPolicyRequest = GetPolicyRequest
						.builder()
						.policyArn(policyArn)
						.build();
					GetPolicyResponse getPolicyResponse = iam.getPolicy(getPolicyRequest);
					String versionId = getPolicyResponse.policy().defaultVersionId();
					
					Policy policy = new Policy();
					policy.setName(attachedPolicy.policyName());
					policy.setArn(policyArn);
					
					GetPolicyVersionRequest getPolicyVersionRequest = GetPolicyVersionRequest
						.builder()
						.policyArn(policyArn)
						.versionId(versionId)
						.build();
					GetPolicyVersionResponse getPolicyVersionResponse = iam.getPolicyVersion(getPolicyVersionRequest);
					
					try {
						String encodedPolicyDocument = getPolicyVersionResponse.policyVersion().document();
						String decodePolicyDocument = URLDecoder.decode(encodedPolicyDocument,UTF_8);
						List<Statement> statementList = parsePolicyDocument(decodePolicyDocument);
						policy.setStatements(statementList);
						policies.add(policy);
					} catch (UnsupportedEncodingException e) {
						System.out.println(e);
					}
				});
			}
		}
		return policies;
	}

	private List<Statement> parsePolicyDocument(String policyDocument) {
		List<Statement> statementList = new ArrayList<>();
		JSONObject obj = new JSONObject(policyDocument);
		JSONArray statements = obj.getJSONArray(STATEMENT);
		for(int i=0; i< statements.length(); i++) {
			Statement policyStatement = new Statement();
			
			JSONObject statement = (JSONObject) statements.get(i);
			
			String effect = statement.getString(EFFECT);
			policyStatement.setEffect(effect);

			Object action = statement.get(ACTION);
			Object resource = statement.get(RESOURCE);
			Object condition = null;
			if(statement.has(CONDITION))
				condition = statement.get(CONDITION);
			
			List<String> actionList = new ArrayList<>();
			List<String> resourceList = new ArrayList<>();
			
			// Parse action(s)
			if(action instanceof JSONArray) {
				JSONArray actionArray = (JSONArray) action;
				actionArray.forEach(actionElement -> {
					actionList.add(actionElement.toString());
				});
			} else {
				actionList.add(action.toString());
			}
			policyStatement.setActions(actionList);

			// Parse resource(s)
			if(resource instanceof JSONArray) {
				JSONArray resourceArray = (JSONArray) resource;
				resourceArray.forEach(resourceElement -> {
					resourceList.add(resourceElement.toString());
				});
			} else {
				resourceList.add(resource.toString());
			}
			policyStatement.setResources(resourceList);

			// Parse conditions
			if(condition != null) {
				JSONObject conditionObj = (JSONObject) condition;
				policyStatement.setCondition(conditionObj.toMap());
			}

			statementList.add(policyStatement);
		}
		return statementList;
	}

	private void uploadToS3(String bucketName, String jsonReport, AwsCredentialsProvider credentialsProvider) {
		Region region = Region.of(awsRegion);
        S3Client s3 = S3Client.builder()
				.credentialsProvider(credentialsProvider)
                .region(region)
                .build();
        
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(IRSA_REPORT_PREFIX+System.currentTimeMillis()+JSON)
				.contentType(APPLICATION_JSON)
                .build();

        s3.putObject(objectRequest, RequestBody.fromString(jsonReport));
	}

	private Credentials getAWSCredentials() throws IOException {

		BufferedReader reader = new BufferedReader(new FileReader(awsWebIdentityTokenFile));
     	String token = reader.readLine();
     	reader.close();
		AssumeRoleWithWebIdentityRequest assumeRoleWithWebIdentityRequest 
			= AssumeRoleWithWebIdentityRequest.builder()
                .webIdentityToken(token)
                .roleArn(awsRoleArn)
                .roleSessionName(IRSA_AUDITOR)
                .build();
		
		StsClient stsClient =  StsClient.builder()
				.region(Region.of(awsRegion))
				.build();
		AssumeRoleWithWebIdentityResponse assumeRoleWithWebIdentityResponse 
			= stsClient.assumeRoleWithWebIdentity(assumeRoleWithWebIdentityRequest);
		
		Credentials credentials = assumeRoleWithWebIdentityResponse.credentials();
		return credentials;
	}

}
