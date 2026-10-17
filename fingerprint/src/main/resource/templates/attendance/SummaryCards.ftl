<style>
    .dash-card { background: #fff; border-radius: 8px; padding: 20px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); text-align: center; margin: 10px; flex: 1; }
    .dash-row { display: flex; justify-content: space-between; }
    .dash-number { font-size: 2.5em; font-weight: bold; color: #2c3e50; }
    .dash-label { color: #7f8c8d; text-transform: uppercase; font-size: 0.9em; }
</style>

<div class="dash-row">
    <div class="dash-card">
        <div class="dash-number">${totalDevices!0}</div>
        <div class="dash-label">Active Devices</div>
    </div>
    <div class="dash-card" style="border-top: 4px solid #3498db;">
        <div class="dash-number">${punchesToday!0}</div>
        <div class="dash-label">Punches Today</div>
    </div>
    <div class="dash-card" style="border-top: 4px solid #e74c3c;">
        <div class="dash-number">${pendingExceptions!0}</div>
        <div class="dash-label">Open Exceptions</div>
    </div>
    <div class="dash-card" style="border-top: 4px solid #f39c12;">
        <div class="dash-number">${failedImports!0}</div>
        <div class="dash-label">Import Errors</div>
    </div>
</div>