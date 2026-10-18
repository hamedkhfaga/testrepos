@echo off
echo Fixing checkstyle violations...

REM Navigate to payroll plugin
cd D:\ofbiz24\ofbiz\plugins\payroll\src\main\java

REM Fix all Java files
for %%f in (*.java) do (
    echo Fixing %%f...
    
    REM Add newline at end of file
    echo. >> "%%f"
    
    REM Remove trailing spaces (using PowerShell)
    powershell -Command "(Get-Content '%%f') | ForEach-Object { $_.TrimEnd() } | Set-Content '%%f'"
    
    REM Fix specific imports
    powershell -Command "(Get-Content '%%f') -replace 'import java\.util\.\*;', 'import java.util.List;^import java.util.Map;^import java.util.HashMap;^import java.util.ArrayList;' | Set-Content '%%f'"
    powershell -Command "(Get-Content '%%f') -replace 'import org.apache.ofbiz.base.util.UtilMisc;', '' | Set-Content '%%f'"
    
    REM Fix module variable name (change 'module' to 'MODULE')
    powershell -Command "(Get-Content '%%f') -replace 'public static final String module =', 'public static final String MODULE =' | Set-Content '%%f'"
)

echo Done! Checkstyle violations should be fixed.
 