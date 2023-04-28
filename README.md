## IRSA Auditor

What is IRSA Auditor?
- A tool to get all the AWS IAM Roles and policies associated with service accounts in an EKS cluster. It scans all the service accounts across all namespaces which have the 'eks.amazonaws.com/role-arn' annotation, gets the IAM role arn, calls IAM APIs to get policies and policy documents, creates a report (sample.json in repo) in json format and uploads to a configured S3 bucket. It can be run as Kubernetes job.

### Installation

**Prerequisites**
- An existing EKS cluster with an OIDC provider
- Helm
- S3 bucket where the reports will be uploaded

Create AWS IAM Role of the IRSA Auditor to make API calls to IAM and S3
```
CLUSTER_NAME=<cluster name>
BUCKET_NAME=<bucket name>
AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query "Account" --output text)

OIDC_PROVIDER=$(aws eks describe-cluster --name $CLUSTER_NAME --query "cluster.identity.oidc.issuer" --output text | sed -e "s/^https:\/\///")
```
Create a trust.json file with the variables you set. Herein we also reference the ArgoCD namespace and make it so all argocd clusterroles can assume the AWS IAM role we’ll create in the next step (system:serviceaccount:*:irsa-auditor-sa)
```
read -r -d '' TRUST_RELATIONSHIP <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::${AWS_ACCOUNT_ID}:oidc-provider/${OIDC_PROVIDER}"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringLike": {
          "${OIDC_PROVIDER}:sub": "system:serviceaccount:*:irsa-auditor-sa",
          "${OIDC_PROVIDER}:aud": "sts.amazonaws.com"
        }
      }
    }
  ]
}
EOF

echo "${TRUST_RELATIONSHIP}" > trust.json
```
Create IAM Role

```
aws iam create-role --role-name irsa-auditor-role --assume-role-policy-document file://trust.json --description "IAM Role to be used by IRSA Auditor"
```

Create policies to allow access to IAM and S3
```
read -r -d '' POLICY <<EOF
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "VisualEditor0",
            "Effect": "Allow",
            "Action": [
                "iam:GetRole",
                "iam:GetPolicyVersion",
                "iam:GetPolicy",
                "iam:ListPolicyVersions",
                "iam:ListAttachedRolePolicies",
                "iam:ListRolePolicies",
                "iam:GetRolePolicy"
            ],
            "Resource": [
                "arn:aws:iam::*:policy/*",
                "arn:aws:iam::*:role/*"
            ]
        },
        {
            "Sid": "VisualEditor1",
            "Effect": "Allow",
            "Action": [
                "iam:ListPolicies",
                "iam:ListRoles"
            ],
            "Resource": "*"
        },
        {
            "Sid": "VisualEditor2",
            "Effect": "Allow",
            "Action": [
                "s3:PutObject",
                "s3:GetObjectAcl",
                "s3:GetObject",
                "s3:GetObjectAttributes",
                "s3:ListBucket",
                "s3:GetBucketPolicy"
            ],
            "Resource": [
                "arn:aws:s3:::$BUCKET_NAME",
                "arn:aws:s3:::$BUCKET_NAME/*"
            ]
        }
    ]
}
EOF

echo "${POLICY}" > policy.json

aws iam put-role-policy --role-name irsa-auditor-role --policy-name AssumeRole --policy-document file://policy.json

export IRSA_ROLE_ARN=$(aws iam get-role --role-name irsa-auditor-role | jq -r .Role.Arn)
```
Run irsa auditor as a K8s job using helm

```
helm install irsa-auditor helm/ --set serviceAccount.annotations."eks\.amazonaws\.com/role-arn"=$IRSA_ROLE_ARN --set bucketName=$BUCKET_NAME
```

Check the job logs for successful completion and download the report from configured S3 bucket.
