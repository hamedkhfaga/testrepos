<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>${reactAppTitle!"Loan Management Workbench"}</title>
    <link rel="stylesheet" href="/loan/loan-react/assets/index.css" />
</head>
<body>
    <div id="root"
         data-user-login-id="${(reactUserLoginId!'')?html}"
         data-company-name="${(reactCompanyName!'')?html}"
         data-organization-party-id="${(reactOrganizationPartyId!'')?html}"
         data-app-title="${(reactAppTitle!'Loan Management Workbench')?html}"
         data-api-base-url="${(reactApiBaseUrl!'/rest/services/')?html}"
         data-copyright="${(reactCopyright!'© 2026 Company')?html}"
         data-access-token="${(reactAccessToken!'')?html}">
    </div>

    <script type="module" src="/loan/loan-react/assets/index.js"></script>
</body>
</html>