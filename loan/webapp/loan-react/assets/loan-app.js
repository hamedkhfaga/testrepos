(function () {
    const ctx = window.OFBIZ_APP_CONTEXT || {};

    const root = document.getElementById("loan-react-root");

    root.innerHTML = 
        <div class="app-shell">

            <header class="app-header">
                <div class="app-header-left">
                    <div class="app-logo">LM</div>
                    <div>
                        <div class="app-title">\</div>
                        <div class="app-subtitle">Apache OFBiz · React Workbench</div>
                    </div>
                </div>

                <div class="app-header-right">
                    <div class="header-meta">
                        <span class="meta-label">Company</span>
                        <span class="meta-value">\</span>
                    </div>
                    <div class="header-meta">
                        <span class="meta-label">User</span>
                        <span class="meta-value">\</span>
                    </div>
                </div>
            </header>

            <main class="app-main">
                <div class="welcome-card">
                    <div class="badge">HELLO WORLD</div>

                    <h1>Loan Management Workbench</h1>

                    <p>
                        React UI successfully initialized inside OFBiz.
                        Ready for Loan Origination, Schedule, Servicing, and Reconciliation modules.
                    </p>

                    <div class="info-grid">
                        <div class="info-box">
                            <div class="info-label">User</div>
                            <div class="info-value">\</div>
                        </div>

                        <div class="info-box">
                            <div class="info-label">Company</div>
                            <div class="info-value">\</div>
                        </div>

                        <div class="info-box">
                            <div class="info-label">API</div>
                            <div class="info-value">\</div>
                        </div>
                    </div>

                    <div class="action-row">
                        <button class="primary-btn">Open Loan Origination</button>
                        <button class="secondary-btn">Dashboard</button>
                    </div>
                </div>
            </main>

            <footer class="app-footer">
                \
            </footer>

        </div>
    ;
})();
