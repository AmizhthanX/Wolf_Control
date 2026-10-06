using System.Runtime.InteropServices;

namespace Wolf.Agent.Services;

/// <summary>
/// Whether Windows currently classifies this machine's network as Private.
///
/// The installer opens the LAN port on the Private profile only, which is
/// right — that port has no business being reachable from a network you do not
/// own. But Windows re-identifies a network whenever the link drops, and a
/// re-identified network defaults to Public, where a Private-scoped rule is
/// completely inert. The rule stays present and Enabled the whole time.
///
/// Observed in the field twice on the same machine: set to Private by hand,
/// reverted to Public the next time the cable was unplugged, and the only
/// symptom either time was that the phone could not reach the agent. Nothing
/// anywhere said why. Reporting it is what turns that into an explanation.
/// </summary>
public static class NetworkProfile
{
    // NLM_CONNECTIVITY: any of the "connected to a network" bits.
    private const int NlmEnumNetworkConnected = 1;

    private enum Category { Public = 0, Private = 1, DomainAuthenticated = 2 }

    /// <summary>
    /// True when every connected network is Private or domain-joined.
    ///
    /// Deliberately pessimistic: one Public network is enough for the firewall
    /// rule to be doing nothing on the interface that matters, and a false
    /// "all fine" here would send someone hunting the wrong problem.
    ///
    /// Returns true if it cannot tell, so a COM failure never produces a
    /// warning about a problem that may not exist.
    /// </summary>
    public static bool IsPrivate(ILogger? log = null)
    {
        try
        {
            var type = Type.GetTypeFromCLSID(new Guid("DCB00C01-570F-4A9B-8D69-199FDBA5723B"));
            if (type is null) return true;

            dynamic? manager = Activator.CreateInstance(type);
            if (manager is null) return true;

            try
            {
                var allPrivate = true;
                var sawAny = false;

                foreach (dynamic network in manager.GetNetworks(NlmEnumNetworkConnected))
                {
                    sawAny = true;
                    var category = (Category)network.GetCategory();
                    if (category == Category.Public) allPrivate = false;
                    Marshal.FinalReleaseComObject(network);
                }

                return !sawAny || allPrivate;
            }
            finally
            {
                Marshal.FinalReleaseComObject(manager);
            }
        }
        catch (Exception ex)
        {
            log?.LogDebug("could not read the network category: {Message}", ex.Message);
            return true;
        }
    }
}
