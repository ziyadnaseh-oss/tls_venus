import 'package:flutter_test/flutter_test.dart';
import 'package:tls_venus/main.dart';

void main() {
  testWidgets('TLS Venus smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(const TlsVenusApp());
    expect(find.text('TLS Venus'), findsOneWidget);
    expect(find.text('DISCONNECTED'), findsOneWidget);
  });
}
