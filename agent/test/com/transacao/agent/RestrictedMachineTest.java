package com.transacao.agent;

import com.transacao.common.remote.InputInjector;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class RestrictedMachineTest {

    public static void main(String[] args) throws Exception {
        testMachineDetection();
        testAgentConfigRestricted();
        testHubDnsFallbackExclusive();
        testInputInjectorDisabledHelper();
        testAutostartRestricted();
        System.out.println("RestrictedMachineTest OK");
    }

    private static void testMachineDetection() {
        assertCheck("os2h-alp-no0344 e reconhecida", RestrictedMachineProfile.isRestrictedMachine("os2h-alp-no0344"));
        assertCheck("OS2H-ALP-NO0344 (maiusculas) e reconhecida", RestrictedMachineProfile.isRestrictedMachine("OS2H-ALP-NO0344"));
        assertCheck("os2h-alp-no0344.corp.local com dominio e reconhecida", RestrictedMachineProfile.isRestrictedMachine("os2h-alp-no0344.corp.local"));
        assertCheck("OS2H-ALP-NO0344.DOMINIO com maiusculas e dominio e reconhecida", RestrictedMachineProfile.isRestrictedMachine("OS2H-ALP-NO0344.DOMINIO"));

        assertCheck("outra maquina nao e reconhecida", !RestrictedMachineProfile.isRestrictedMachine("desktop-outro"));
        assertCheck("sufixo diferente nao e reconhecido", !RestrictedMachineProfile.isRestrictedMachine("os2h-alp-no0345"));
        assertCheck("prefixo diferente nao e reconhecido", !RestrictedMachineProfile.isRestrictedMachine("server-os2h-alp-no0344"));
    }

    private static void testAgentConfigRestricted() {
        AgentConfig cfg = new AgentConfig("wss://servidor.tail074692.ts.net:8787/agent",
                "wss://servidor.tail074692.ts.net:8787/agent",
                "10.0.0.1", "", "", "", "id", "secret", new File("."), true);

        assertCheck("config restricted e true", cfg.restricted);
        assertCheck("IP interno e fixado em 192.168.16.253", "192.168.16.253".equals(cfg.hubIp));
    }

    private static void testHubDnsFallbackExclusive() throws Exception {
        HubDnsFallback.configure("servidor.tail074692.ts.net", "192.168.16.253", true);
        assertCheck("HubDnsFallback esta em modo exclusivo", HubDnsFallback.isExclusive());

        InetAddressResolver mockBuiltin = new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy) throws UnknownHostException {
                throw new UnknownHostException("builtin nao deve ser chamado no modo exclusivo para o hub");
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return "builtin-addr";
            }
        };

        InetAddressResolver resolver = HubDnsFallback.resolver(mockBuiltin);
        List<InetAddress> addrs = resolver.lookupByName("servidor.tail074692.ts.net", null).collect(Collectors.toList());
        assertCheck("retorna exatamente 1 endereco", addrs.size() == 1);
        assertCheck("o endereco resolvido e 192.168.16.253", "192.168.16.253".equals(addrs.get(0).getHostAddress()));

        // Limpa estado para nao afetar outros testes
        HubDnsFallback.configure("", "");
    }

    private static void testInputInjectorDisabledHelper() throws Exception {
        InputInjector injector = new InputInjector();
        injector.disableNativeHelper();
        assertCheck("native helper desativado", !injector.isNativeHelperEnabled());
        assertCheck("prepareNativeKeyboard retorna false sem iniciar powershell", !injector.prepareNativeKeyboard());
        assertCheck("keyPressPhysical retorna false", !injector.keyPressPhysical(30, false));
        assertCheck("keyReleasePhysical retorna false", !injector.keyReleasePhysical(30, false));
    }

    private static void testAutostartRestricted() throws Exception {
        System.setProperty("transacao.restricted.machine", "1");
        try {
            AgentSettings s = new AgentSettings();
            assertCheck("AgentSettings localOnly e true para maquina restrita", s.localOnly);
            assertCheck("AgentSettings webrtcEnabled e false para maquina restrita", !s.webrtcEnabled);
            assertCheck("AgentSettings nativeKeyboardHelper e false para maquina restrita", !s.nativeKeyboardHelper);
            assertCheck("AgentSettings startWithSystem e false para maquina restrita", !s.startWithSystem);
            assertCheck("AgentSettings autoUpdate e false para maquina restrita", !s.autoUpdate);

            File tmp = File.createTempFile("test-settings", ".properties");
            try {
                s.saveLocal(tmp);
                AgentSettings loaded = new AgentSettings();
                loaded.loadLocal(tmp);
                assertCheck("loadLocal preserva localOnly", loaded.localOnly);
                assertCheck("loadLocal preserva webrtcEnabled", !loaded.webrtcEnabled);
                assertCheck("loadLocal preserva nativeKeyboardHelper", !loaded.nativeKeyboardHelper);
                assertCheck("loadLocal preserva startWithSystem", !loaded.startWithSystem);
                assertCheck("loadLocal preserva autoUpdate", !loaded.autoUpdate);
            } finally {
                tmp.delete();
            }
        } finally {
            System.clearProperty("transacao.restricted.machine");
        }
    }

    private static void assertCheck(String msg, boolean condition) {
        if (!condition) {
            throw new AssertionError("FALHA: " + msg);
        }
        System.out.println("  ok  " + msg);
    }
}
