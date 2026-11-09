namespace GreetConsole;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        string logPath = ReadStringArgument(args, "--log") ?? Path.Combine(AppContext.BaseDirectory, "latest.log");
        int commandPort = int.TryParse(ReadStringArgument(args, "--command-port"), out int parsedPort) ? parsedPort : 0;
        string commandToken = ReadStringArgument(args, "--command-token") ?? "";

        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm(logPath, commandPort, commandToken));
    }

    private static string? ReadStringArgument(string[] args, string name)
    {
        for (int i = 0; i + 1 < args.Length; i++)
        {
            if (string.Equals(args[i], name, StringComparison.OrdinalIgnoreCase))
                return args[i + 1];
        }

        return null;
    }
}
