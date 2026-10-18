<style>
    /* Force this specific portlet container to be 100% width */
    .summary-container {
        display: flex;
        flex-wrap: wrap;
        gap: 20px;
        width: 100%;
        margin-bottom: 30px;
        clear: both;
    }
    .summary-card {
        flex: 1;
        min-width: 200px;
        padding: 20px;
        border-radius: 8px;
        color: white;
        text-align: center;
        box-shadow: 0 4px 6px rgba(0,0,0,0.1);
    }
    .card-title { font-size: 14px; font-weight: bold; text-transform: uppercase; opacity: 0.9; }
    .card-value { font-size: 32px; font-weight: bold; margin: 10px 0; }
    
    /* This forces the NEXT portal part (the Chart) to move to a NEW ROW */
    .row-breaker {
        clear: both !important;
        display: block !important;
        height: 1px;
        width: 100%;
    }
</style>

<div class="summary-container">
    <div class="summary-card" style="background-color: #3498db;">
        <div class="card-title">Total Employees</div>
        <div class="card-value">${totalEmployees!0}</div>
    </div>

    <div class="summary-card" style="background-color: #2ecc71;">
        <div class="card-title">Present Today</div>
        <div class="card-value">${presentToday!0}</div>
    </div>

    <div class="summary-card" style="background-color: #f39c12;">
        <div class="card-title">Late Today</div>
        <div class="card-value">${lateToday!0}</div>
    </div>

    <div class="summary-card" style="background-color: #e74c3c;">
        <div class="card-title">Offline Devices</div>
        <div class="card-value">${offlineDevices!0}</div>
    </div>
</div>

<div class="row-breaker"></div>