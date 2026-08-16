/*
ESPAUTO
Copyright (c) 2026 honoz
Licensed under the MIT License.
*/
using System;
using System.IO;
using Microsoft.UI.Xaml;

namespace ESPAUTO;

public partial class App : Application
{
    private Window? _window;

    public App()
    {
        this.InitializeComponent();
        this.UnhandledException += OnUnhandledException;
    }

    private void OnUnhandledException(object sender, Microsoft.UI.Xaml.UnhandledExceptionEventArgs e)
    {
        try
        {
            var logPath = Path.Combine(Path.GetTempPath(), "ESPAUTO_crash.log");
            var msg = $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] Unhandled Exception:\n{e.Exception}\n\n";
            File.AppendAllText(logPath, msg);
        }
        catch { }
        e.Handled = true;
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        _window = new MainWindow();
        _window.Activate();
    }
}
