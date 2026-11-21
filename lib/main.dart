import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const TlsVenusApp());
}

class TlsVenusApp extends StatelessWidget {
  const TlsVenusApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'TLS Venus VPN',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.dark,
      darkTheme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF0F172A), // Slate 900
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF38BDF8), // Sky 400
          secondary: Color(0xFF818CF8), // Indigo 400
          surface: Color(0xFF1E293B), // Slate 800
          error: Color(0xFFF87171),
        ),
        cardTheme: CardThemeData(
          color: const Color(0xFF1E293B),
          elevation: 2,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
            side: const BorderSide(color: Color(0xFF334155), width: 1),
          ),
        ),
        inputDecorationTheme: InputDecorationTheme(
          filled: true,
          fillColor: const Color(0xFF0F172A),
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: const BorderSide(color: Color(0xFF334155)),
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: const BorderSide(color: Color(0xFF334155)),
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: const BorderSide(color: Color(0xFF38BDF8), width: 2),
          ),
          contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        ),
      ),
      home: const VpnHomeScreen(),
    );
  }
}

class VpnHomeScreen extends StatefulWidget {
  const VpnHomeScreen({super.key});

  @override
  State<VpnHomeScreen> createState() => _VpnHomeScreenState();
}

class _VpnHomeScreenState extends State<VpnHomeScreen> {
  static const MethodChannel _methodChannel = MethodChannel('com.example.tls_venus/vpn');
  static const EventChannel _eventChannel = EventChannel('com.example.tls_venus/vpn_events');

  // Controllers
  final TextEditingController _hostController = TextEditingController(text: '127.0.0.1');
  final TextEditingController _portController = TextEditingController(text: '443');
  final TextEditingController _sniController = TextEditingController(text: 'cloudflare.com');
  final TextEditingController _payloadController = TextEditingController(
    text: 'CONNECT [host_port] HTTP/1.1[crlf]Host: [sni][crlf]Connection: Upgrade[crlf][crlf]',
  );
  final TextEditingController _dnsController = TextEditingController(text: '8.8.8.8');

  final _formKey = GlobalKey<FormState>();

  // State
  String _connectionState = 'DISCONNECTED'; // DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING
  int _speedUp = 0;
  int _speedDown = 0;
  int _totalUp = 0;
  int _totalDown = 0;
  int _durationSec = 0;

  StreamSubscription? _eventSubscription;

  @override
  void initState() {
    super.initState();
    _loadSavedConfig();
    _initEventStream();
  }

  @override
  void dispose() {
    _eventSubscription?.cancel();
    _hostController.dispose();
    _portController.dispose();
    _sniController.dispose();
    _payloadController.dispose();
    _dnsController.dispose();
    super.dispose();
  }

  Future<void> _loadSavedConfig() async {
    try {
      final String? jsonStr = await _methodChannel.invokeMethod<String>('loadConfig');
      if (jsonStr != null && jsonStr.isNotEmpty) {
        final Map<String, dynamic> data = jsonDecode(jsonStr);
        setState(() {
          if (data['serverHost'] != null) _hostController.text = data['serverHost'];
          if (data['serverPort'] != null) _portController.text = data['serverPort'].toString();
          if (data['sni'] != null) _sniController.text = data['sni'];
          if (data['customPayload'] != null) _payloadController.text = data['customPayload'];
          if (data['dnsServer'] != null) _dnsController.text = data['dnsServer'];
        });
      }
      final String? status = await _methodChannel.invokeMethod<String>('getStatus');
      if (status != null) {
        setState(() => _connectionState = status);
      }
    } catch (e) {
      debugPrint('Error loading config: $e');
    }
  }

  Future<void> _saveCurrentConfig() async {
    final Map<String, dynamic> config = {
      'serverHost': _hostController.text.trim(),
      'serverPort': int.tryParse(_portController.text.trim()) ?? 443,
      'sni': _sniController.text.trim(),
      'customPayload': _payloadController.text,
      'dnsServer': _dnsController.text.trim(),
    };
    try {
      await _methodChannel.invokeMethod('saveConfig', jsonEncode(config));
    } catch (e) {
      debugPrint('Error saving config: $e');
    }
  }

  void _initEventStream() {
    _eventSubscription = _eventChannel.receiveBroadcastStream().listen(
      (dynamic event) {
        if (event is Map) {
          final type = event['type'];
          if (type == 'state') {
            setState(() {
              _connectionState = event['state'] as String? ?? 'DISCONNECTED';
              if (_connectionState == 'DISCONNECTED') {
                _speedUp = 0;
                _speedDown = 0;
                _durationSec = 0;
              }
            });
          } else if (type == 'stats') {
            setState(() {
              _connectionState = event['state'] as String? ?? 'CONNECTED';
              _speedUp = (event['speedUp'] as num?)?.toInt() ?? 0;
              _speedDown = (event['speedDown'] as num?)?.toInt() ?? 0;
              _totalUp = (event['totalUp'] as num?)?.toInt() ?? 0;
              _totalDown = (event['totalDown'] as num?)?.toInt() ?? 0;
              _durationSec = (event['duration'] as num?)?.toInt() ?? 0;
            });
          }
        }
      },
      onError: (dynamic error) {
        debugPrint('VPN event stream error: $error');
      },
    );
  }

  Future<void> _toggleVpn() async {
    if (_connectionState == 'CONNECTED' || _connectionState == 'CONNECTING') {
      try {
        await _methodChannel.invokeMethod('stopVpn');
      } catch (e) {
        _showSnackBar('Error stopping VPN: $e');
      }
    } else {
      if (!_formKey.currentState!.validate()) return;
      await _saveCurrentConfig();

      try {
        // Prepare VPN permission
        final bool? prepared = await _methodChannel.invokeMethod<bool>('prepareVpn');
        if (prepared != true) {
          _showSnackBar('VPN permission not granted.');
          return;
        }

        final Map<String, dynamic> config = {
          'serverHost': _hostController.text.trim(),
          'serverPort': int.tryParse(_portController.text.trim()) ?? 443,
          'sni': _sniController.text.trim(),
          'customPayload': _payloadController.text,
          'dnsServer': _dnsController.text.trim(),
        };

        await _methodChannel.invokeMethod('startVpn', config);
      } catch (e) {
        _showSnackBar('Failed to connect: $e');
      }
    }
  }

  void _insertPayloadMacro(String macro) {
    final text = _payloadController.text;
    final selection = _payloadController.selection;
    if (selection.start >= 0) {
      final newText = text.replaceRange(selection.start, selection.end, macro);
      _payloadController.value = TextEditingValue(
        text: newText,
        selection: TextSelection.collapsed(offset: selection.start + macro.length),
      );
    } else {
      _payloadController.text += macro;
    }
  }

  void _showSnackBar(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: const Color(0xFF1E293B),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  String _formatBytes(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    if (bytes < 1024 * 1024 * 1024) return '${(bytes / (1024 * 1024)).toStringAsFixed(2)} MB';
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(2)} GB';
  }

  String _formatSpeed(int bytesPerSec) {
    if (bytesPerSec < 1024) return '$bytesPerSec B/s';
    if (bytesPerSec < 1024 * 1024) return '${(bytesPerSec / 1024).toStringAsFixed(1)} KB/s';
    return '${(bytesPerSec / (1024 * 1024)).toStringAsFixed(2)} MB/s';
  }

  String _formatDuration(int seconds) {
    final hrs = (seconds ~/ 3600).toString().padLeft(2, '0');
    final mins = ((seconds % 3600) ~/ 60).toString().padLeft(2, '0');
    final secs = (seconds % 60).toString().padLeft(2, '0');
    return '$hrs:$mins:$secs';
  }

  Color _getStatusColor() {
    switch (_connectionState) {
      case 'CONNECTED':
        return const Color(0xFF10B981); // Emerald 500
      case 'CONNECTING':
        return const Color(0xFFF59E0B); // Amber 500
      case 'DISCONNECTING':
        return const Color(0xFFEF4444); // Red 500
      default:
        return const Color(0xFF64748B); // Slate 500
    }
  }

  @override
  Widget build(BuildContext context) {
    final isConnected = _connectionState == 'CONNECTED';
    final isConnecting = _connectionState == 'CONNECTING';
    final statusColor = _getStatusColor();

    return Scaffold(
      appBar: AppBar(
        backgroundColor: const Color(0xFF0F172A),
        elevation: 0,
        title: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: const Color(0xFF38BDF8).withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(10),
              ),
              child: const Icon(Icons.shield_outlined, color: Color(0xFF38BDF8), size: 22),
            ),
            const SizedBox(width: 12),
            const Text(
              'TLS Venus',
              style: TextStyle(fontWeight: FontWeight.bold, fontSize: 20),
            ),
          ],
        ),
        actions: [
          IconButton(
            tooltip: 'Save Configuration',
            icon: const Icon(Icons.save_outlined),
            onPressed: () async {
              await _saveCurrentConfig();
              _showSnackBar('Configuration saved successfully');
            },
          ),
          IconButton(
            tooltip: 'Reset to Defaults',
            icon: const Icon(Icons.restart_alt),
            onPressed: () {
              setState(() {
                _hostController.text = '127.0.0.1';
                _portController.text = '443';
                _sniController.text = 'cloudflare.com';
                _payloadController.text =
                    'CONNECT [host_port] HTTP/1.1[crlf]Host: [sni][crlf]Connection: Upgrade[crlf][crlf]';
                _dnsController.text = '8.8.8.8';
              });
              _saveCurrentConfig();
              _showSnackBar('Defaults restored');
            },
          ),
        ],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          child: Form(
            key: _formKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // 1. Connection Status & Primary Action Hero Card
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(20),
                    child: Column(
                      children: [
                        // Status badge & duration
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Row(
                              children: [
                                Container(
                                  width: 12,
                                  height: 12,
                                  decoration: BoxDecoration(
                                    color: statusColor,
                                    shape: BoxShape.circle,
                                    boxShadow: [
                                      BoxShadow(
                                        color: statusColor.withValues(alpha: 0.6),
                                        blurRadius: 8,
                                        spreadRadius: 2,
                                      ),
                                    ],
                                  ),
                                ),
                                const SizedBox(width: 10),
                                Text(
                                  _connectionState,
                                  style: TextStyle(
                                    fontWeight: FontWeight.bold,
                                    fontSize: 16,
                                    color: statusColor,
                                    letterSpacing: 0.5,
                                  ),
                                ),
                              ],
                            ),
                            if (isConnected)
                              Container(
                                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                                decoration: BoxDecoration(
                                  color: const Color(0xFF334155),
                                  borderRadius: BorderRadius.circular(20),
                                ),
                                child: Text(
                                  _formatDuration(_durationSec),
                                  style: const TextStyle(
                                    fontFamily: 'monospace',
                                    fontWeight: FontWeight.w600,
                                    color: Colors.white,
                                  ),
                                ),
                              ),
                          ],
                        ),
                        const SizedBox(height: 24),

                        // Connect / Disconnect Action Button
                        SizedBox(
                          width: double.infinity,
                          height: 54,
                          child: ElevatedButton(
                            onPressed: isConnecting ? null : _toggleVpn,
                            style: ElevatedButton.styleFrom(
                              backgroundColor: isConnected
                                  ? const Color(0xFFEF4444)
                                  : const Color(0xFF0284C7),
                              foregroundColor: Colors.white,
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(14),
                              ),
                              elevation: 4,
                            ),
                            child: isConnecting
                                ? const SizedBox(
                                    width: 24,
                                    height: 24,
                                    child: CircularProgressIndicator(
                                      strokeWidth: 2.5,
                                      color: Colors.white,
                                    ),
                                  )
                                : Row(
                                    mainAxisAlignment: MainAxisAlignment.center,
                                    children: [
                                      Icon(
                                        isConnected
                                            ? Icons.power_settings_new
                                            : Icons.lock_outline,
                                        size: 22,
                                      ),
                                      const SizedBox(width: 10),
                                      Text(
                                        isConnected ? 'DISCONNECT' : 'CONNECT TUNNEL',
                                        style: const TextStyle(
                                          fontWeight: FontWeight.bold,
                                          fontSize: 16,
                                          letterSpacing: 1.0,
                                        ),
                                      ),
                                    ],
                                  ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),

                // 2. Real-time Traffic Statistics
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'LIVE TRAFFIC METRICS',
                          style: TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.w600,
                            color: Color(0xFF94A3B8),
                            letterSpacing: 1.1,
                          ),
                        ),
                        const SizedBox(height: 16),
                        Row(
                          children: [
                            // Download
                            Expanded(
                              child: _buildMetricTile(
                                icon: Icons.arrow_downward,
                                iconColor: const Color(0xFF38BDF8),
                                title: 'DOWNLOAD',
                                speed: _formatSpeed(_speedDown),
                                total: _formatBytes(_totalDown),
                              ),
                            ),
                            Container(
                              height: 50,
                              width: 1,
                              color: const Color(0xFF334155),
                            ),
                            // Upload
                            Expanded(
                              child: _buildMetricTile(
                                icon: Icons.arrow_upward,
                                iconColor: const Color(0xFF818CF8),
                                title: 'UPLOAD',
                                speed: _formatSpeed(_speedUp),
                                total: _formatBytes(_totalUp),
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),

                // 3. Server & Tunneling Configuration
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'TUNNEL CONFIGURATION',
                          style: TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.w600,
                            color: Color(0xFF94A3B8),
                            letterSpacing: 1.1,
                          ),
                        ),
                        const SizedBox(height: 16),

                        // Host and Port Row
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Expanded(
                              flex: 3,
                              child: TextFormField(
                                controller: _hostController,
                                enabled: !isConnected,
                                decoration: const InputDecoration(
                                  labelText: 'Server Host / IP',
                                  hintText: 'e.g. 198.51.100.1',
                                  prefixIcon: Icon(Icons.dns_outlined, size: 20),
                                ),
                                validator: (value) =>
                                    value == null || value.trim().isEmpty ? 'Required' : null,
                              ),
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              flex: 1,
                              child: TextFormField(
                                controller: _portController,
                                enabled: !isConnected,
                                keyboardType: TextInputType.number,
                                decoration: const InputDecoration(
                                  labelText: 'Port',
                                  hintText: '443',
                                ),
                                validator: (value) {
                                  if (value == null || value.trim().isEmpty) return 'Required';
                                  final p = int.tryParse(value);
                                  if (p == null || p <= 0 || p > 65535) return 'Invalid';
                                  return null;
                                },
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 16),

                        // SNI (Server Name Indication)
                        TextFormField(
                          controller: _sniController,
                          enabled: !isConnected,
                          decoration: const InputDecoration(
                            labelText: 'SNI Hostname (Server Name Indication)',
                            hintText: 'e.g. cloudflare.com or zero-rate host',
                            prefixIcon: Icon(Icons.lock_outline, size: 20),
                          ),
                        ),
                        const SizedBox(height: 16),

                        // DNS Server
                        TextFormField(
                          controller: _dnsController,
                          enabled: !isConnected,
                          decoration: const InputDecoration(
                            labelText: 'DNS Server',
                            hintText: '8.8.8.8',
                            prefixIcon: Icon(Icons.public, size: 20),
                          ),
                        ),
                        const SizedBox(height: 16),

                        // Custom Payload / HTTP Headers
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            const Text(
                              'Custom Payload / Headers (Optional)',
                              style: TextStyle(
                                fontSize: 13,
                                fontWeight: FontWeight.w500,
                                color: Color(0xFFCBD5E1),
                              ),
                            ),
                            if (!isConnected)
                              TextButton(
                                onPressed: () {
                                  _payloadController.clear();
                                },
                                child: const Text('Clear', style: TextStyle(fontSize: 12)),
                              ),
                          ],
                        ),
                        const SizedBox(height: 6),
                        TextFormField(
                          controller: _payloadController,
                          enabled: !isConnected,
                          maxLines: 4,
                          style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                          decoration: const InputDecoration(
                            hintText: 'CONNECT [host_port] HTTP/1.1[crlf]Host: [sni][crlf][crlf]',
                          ),
                        ),
                        const SizedBox(height: 8),

                        // Quick Macro Insert Chips
                        if (!isConnected)
                          Wrap(
                            spacing: 8,
                            runSpacing: 4,
                            children: [
                              _buildMacroChip('[host_port]'),
                              _buildMacroChip('[sni]'),
                              _buildMacroChip('[crlf]'),
                              _buildMacroChip('CONNECT'),
                              _buildMacroChip('Upgrade: websocket'),
                            ],
                          ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 24),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildMetricTile({
    required IconData icon,
    required Color iconColor,
    required String title,
    required String speed,
    required String total,
  }) {
    return Column(
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 16, color: iconColor),
            const SizedBox(width: 6),
            Text(
              title,
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.bold,
                color: iconColor,
              ),
            ),
          ],
        ),
        const SizedBox(height: 6),
        Text(
          speed,
          style: const TextStyle(
            fontSize: 18,
            fontWeight: FontWeight.bold,
            fontFamily: 'monospace',
          ),
        ),
        const SizedBox(height: 2),
        Text(
          'Total: $total',
          style: const TextStyle(
            fontSize: 12,
            color: Color(0xFF94A3B8),
          ),
        ),
      ],
    );
  }

  Widget _buildMacroChip(String label) {
    return ActionChip(
      label: Text(label, style: const TextStyle(fontSize: 11, fontFamily: 'monospace')),
      backgroundColor: const Color(0xFF334155),
      labelPadding: const EdgeInsets.symmetric(horizontal: 4),
      visualDensity: VisualDensity.compact,
      onPressed: () => _insertPayloadMacro(label),
    );
  }
}
