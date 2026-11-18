# Deploying to AWS

A step-by-step guide to deploying the minimal AWS setup in [infra/terraform](../infra/terraform), starting from no AWS account at all.

**What you get:** one container on ECS Fargate and a private RDS PostgreSQL database in `ca-central-1` (Montreal), with the database password in Secrets Manager and a monthly budget alert.

**Cost:** about **$40/month** if left running (estimate from AWS on-demand prices; confirm with the [AWS Pricing Calculator](https://calculator.aws/)). Tear it down when you're not using it (step 9).

**Not production-ready:** no HTTPS, no authentication, single instance. See the [full list of gaps](DECISIONS.md#2026-11-17-what-the-minimal-aws-setup-is-not-ready-for).

> AWS changes its console from time to time. If a button or menu name below doesn't match exactly, look for the closest equivalent.

---

## 1. Create an AWS account

1. Go to https://aws.amazon.com and choose **Create an AWS Account**.
2. Enter an email address and an account name (e.g. `payments-ledger`).
3. Verify the email, set a strong **root user** password, and enter contact and payment details. A card is required even for free-tier use.
4. Verify your phone number, and choose the **Basic (free)** support plan.
5. Wait for the "account is ready" email. This can take a few minutes, and occasionally longer.

## 2. Secure the root user

The root user can do anything, including closing the account, so use it only for this setup.

1. Sign in as the root user at https://console.aws.amazon.com.
2. Open **IAM**, then add **MFA** for the root user (an authenticator app is fine).
3. Do **not** create access keys for the root user.

## 3. Turn on billing alerts

The Terraform also creates a budget, but set up an account-wide safety net now, before anything is running.

1. Open **Billing and Cost Management**, then **Budgets**, then **Create budget**.
2. Choose a **Monthly cost budget** (or the "zero spend" template) and enter your email.
3. Optional: in **Billing preferences**, turn on free-tier usage alerts and PDF invoices by email.

## 4. Create an admin user for daily use

Deploy as a normal user, not root. AWS recommends **IAM Identity Center**, which gives you short-lived credentials instead of long-lived keys.

1. Switch the console region (top-right) to **Canada (Central) ca-central-1**.
2. Open **IAM Identity Center** and choose **Enable**.
3. Under **Users**, choose **Add user**, then enter your username and email.
4. Under **Permission sets**, choose **Create permission set**, then the predefined **AdministratorAccess**.
5. Under **AWS accounts**, select your account, choose **Assign users or groups**, and pick your user and the `AdministratorAccess` permission set.
6. Accept the invitation email, set a password, and register **MFA**.
7. Note your **AWS access portal URL** (on the IAM Identity Center dashboard, e.g. `https://d-xxxxxxxxxx.awsapps.com/start`).

> **Simpler but less secure alternative:** create an IAM user with `AdministratorAccess` and an access key, then use `aws configure` instead of `aws configure sso` in step 5. Never commit the key.

## 5. Install the tools (Windows)

Use **Git Bash** for all commands in this guide. It comes with Git for Windows.

1. Install **Docker Desktop** from https://www.docker.com/products/docker-desktop/ and make sure it's running.
2. Install Terraform and the AWS CLI:
   ```bash
   winget install Hashicorp.Terraform Amazon.AWSCLI
   ```
3. Close and reopen Git Bash, then check that they're installed:
   ```bash
   terraform -version     # 1.6 or newer
   aws --version
   docker version
   ```
4. Connect the AWS CLI to your Identity Center user:
   ```bash
   aws configure sso
   #   SSO session name:     payments
   #   SSO start URL:        <your AWS access portal URL from step 4>
   #   SSO region:           ca-central-1
   #   (a browser opens: sign in and allow access)
   #   CLI default region:   ca-central-1
   #   CLI output format:    json
   #   Profile name:         payments
   ```
5. Use that profile in this terminal, and check that it works:
   ```bash
   export AWS_PROFILE=payments
   aws sts get-caller-identity       # shows your account id and user
   ```
   When the session expires (after several hours), run `aws sso login --profile payments` again.

## 6. Get the code and configure it

1. Clone the repository and check out `main`. The deployment files must be merged into `main` first.
   ```bash
   git clone https://github.com/cabasoah/Payments-and-ledger-service-Java-.git
   cd Payments-and-ledger-service-Java-
   ```
2. Make sure CI is green on the commit you're deploying (the badge on the README).
3. Create your Terraform variables file. It's git-ignored, so it stays on your machine.
   ```bash
   cp infra/terraform/terraform.tfvars.example infra/terraform/terraform.tfvars
   ```
   Edit it and set `alert_email` to your email. Optionally change `monthly_budget_usd` (default 25).

## 7. Deploy

```bash
export AWS_PROFILE=payments      # if not already set in this terminal
./scripts/deploy.sh
```

What it does:
1. Runs `terraform init`.
2. Creates the ECR image repository. Type `yes` when Terraform asks.
3. Builds the Docker image, tags it with the git commit, and pushes it to ECR.
4. Creates everything else: the database, secret, ECS cluster and service, and budget. Type `yes` again.
5. Waits until the service is stable, then prints the app's URL.

The first run takes about **10–15 minutes**, mostly creating the database.

> **Keep `infra/terraform/terraform.tfstate`.** It's how Terraform knows what it created, and it's needed for teardown. It also contains the database password, so never commit or share it. It's git-ignored.

## 8. Check that it works

```bash
./scripts/app-url.sh                         # prints the App, Swagger UI and Health URLs
URL=http://<ip-from-above>:8080

curl -s $URL/actuator/health                 # {"status":"UP",...}
```

Open `$URL/swagger-ui.html` in a browser, or run the payment walkthrough:

```bash
MERCHANT=$(curl -s -X POST $URL/v1/merchants \
  -H 'Content-Type: application/json' -d '{"name":"Acme Coffee"}' | jq -r .id)
PAYMENT=$(curl -s -X POST $URL/v1/payments -H 'Content-Type: application/json' \
  -d "{\"merchantId\":\"$MERCHANT\",\"amount\":1050,\"currency\":\"CAD\",\"cardToken\":\"tok_visa_ok\"}" | jq -r .id)
curl -s -X POST $URL/v1/payments/$PAYMENT/capture
curl -s $URL/v1/payments/$PAYMENT
```

(`jq` is optional: `winget install jqlang.jq`. Without it, copy the ids by hand.)

**View the logs:**
```bash
aws logs tail /ecs/payments-ledger --follow --region ca-central-1
```

**Redeploy after a code change:** commit it, then run `./scripts/deploy.sh` again. The IP address changes after every deploy, so run `./scripts/app-url.sh` to get the new one.

## 9. Tear it down

```bash
export AWS_PROFILE=payments
./scripts/teardown.sh        # type 'yes' to confirm; deletes everything, including the database
```

Then confirm nothing is left:
1. In the console (region **ca-central-1**), check that **ECS**, **RDS**, **ECR** and **Secrets Manager** have no `payments-ledger` resources.
2. The next day, check **Billing and Cost Management**, then **Bills**, for any ongoing charges.

---

## Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| `Unable to locate credentials` / `ExpiredToken` | The SSO session expired. Run `aws sso login --profile payments` and `export AWS_PROFILE=payments`. |
| `Missing required tool: terraform` | Reopen Git Bash after installing, or check your `PATH`. |
| `Cannot connect to the Docker daemon` | Start Docker Desktop. |
| First apply fails creating the ECS cluster or service with a service-linked role error | Common on brand-new accounts: AWS creates the ECS service role on first use. Wait a minute and re-run `./scripts/deploy.sh`. |
| `deploy.sh` stops partway | Fix the error and re-run it. Terraform picks up where it left off. |
| Service never becomes stable | Run `aws logs tail /ecs/payments-ledger --region ca-central-1`. The usual causes are the database not being ready yet (wait and re-run) or a failing app health check. |
| `app-url.sh` says no running task | The task is restarting or failed to start. Check the logs, and in the console check **ECS**, then **Clusters**, then `payments-ledger`, then **Tasks** for "stopped reason". |
| Browser can't reach the URL | Use `http://` (not https) and port `8080`, and make sure you have the current IP from `app-url.sh`. |
| Budget alert email | Expected if it's left running (~$40/month vs the $25 budget). Tear down if you're not using it. |
| Lost `terraform.tfstate` | Terraform can no longer delete what it created. Delete the `payments-ledger` resources by hand in the console: ECS service and cluster, RDS instance, ECR repository, Secrets Manager secret, security groups, IAM role, log group, budget. |
