import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:management_app/main.dart';
import 'package:management_app/services.dart';

const _smsChannel = MethodChannel('organizator/sms');

void main() {
  group('model', () {
    test('mesajul de sincronizare se citește înapoi identic', () {
      const list = [
        SalonService(name: 'Semipermanentă', durationMin: 60),
        SalonService(name: 'Gel', durationMin: 90),
      ];
      final parsed = parseServicesSyncMessage(servicesSyncMessage(45, list))!;
      expect(parsed.defaultMin, 45);
      expect(parsed.services, list);
    });

    test('mesaj invalid sau alt prefix → null', () {
      expect(parseServicesSyncMessage('ORG:A:{}'), isNull);
      expect(parseServicesSyncMessage('ORG:V:nu'), isNull);
      expect(parseServicesSyncMessage('ORG:V:{"d":0,"s":[]}'), isNull);
    });

    test('serviciile invalide sunt sărite', () {
      final s = decodeServices([
        {'n': '', 'm': 30},
        {'n': 'X', 'm': 2},
        {'n': 'Ok', 'm': 60},
      ]);
      expect(s, [const SalonService(name: 'Ok', durationMin: 60)]);
    });

    test('programarea își păstrează serviciul și durata (local și în sync)',
        () {
      final item = Item(
        syncId: 'abc',
        number: 1,
        name: 'Ana',
        description: '',
        createdAt: DateTime(2030, 1, 1, 9),
        expiresAt: DateTime(2030, 1, 1, 10, 30),
        service: 'Gel',
        durationMin: 90,
      );
      final local = Item.fromJson(item.toJson());
      expect((local.service, local.durationMin), ('Gel', 90));
      final sync = Item.fromSyncJson(item.toSyncJson());
      expect((sync.service, sync.durationMin), ('Gel', 90));
      expect(item.toSyncJson()['v'], 'Gel');
      expect(item.toSyncJson()['m'], 90);
    });

    test('programarea veche, fără durată, folosește durata tabelului', () {
      final old = Item.fromJson({
        'syncId': 'x',
        'number': 1,
        'name': 'Ion',
        'description': '',
        'createdAt': '2030-01-01T09:00:00.000',
      });
      expect(old.service, isNull);
      expect(old.durationOr(30), 30);
      expect(old.toSyncJson().containsKey('m'), isFalse);
      expect(old.copyWith(service: 'Gel', durationMin: 90).durationOr(30), 90);
      expect(
          old
              .copyWith(service: 'Gel', durationMin: 90)
              .copyWith(clearService: true)
              .durationMin,
          isNull);
    });

    test('formatul duratei', () {
      expect(formatServiceDuration(45), '45 min');
      expect(formatServiceDuration(60), '1 oră');
      expect(formatServiceDuration(120), '2 ore');
      expect(formatServiceDuration(90), '1h 30min');
    });
  });

  group('în aplicație', () {
    late List<MethodCall> calls;
    late List<Map<String, String>> queue;

    setUp(() {
      SharedPreferences.setMockInitialValues({
        'management_boards': jsonEncode([
          for (var i = 1; i <= 10; i++) {'id': 'b$i', 'name': 'Tabel $i'},
        ]),
        'management_active_board': 'b1',
        'management_items_b1': '[]',
        'sync_partner_phone_b1': '0722000111',
        'sync_secret_b1': 'AAAA2222',
      });
      debugSimulateAndroid = true;
      calls = [];
      queue = [];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(_smsChannel, (call) async {
        calls.add(call);
        switch (call.method) {
          case 'getSyncMessages':
            return jsonEncode(queue);
          case 'ackSyncMessages':
            final ids = (call.arguments['ids'] as List).cast<String>();
            queue.removeWhere((e) => ids.contains(e['id']));
            return null;
          case 'sendSync':
            return true;
          case 'computeFreeSlots':
            return '[]';
        }
        return null;
      });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(_smsChannel, null);
      debugSimulateAndroid = null;
    });

    Future<void> settle(WidgetTester tester) async {
      for (var i = 0; i < 10; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
    }

    List<String> sentSync() => calls
        .where((c) => c.method == 'sendSync')
        .map((c) => (c.arguments as Map)['message'] as String)
        .toList();

    Future<void> openServices(WidgetTester tester) async {
      tester.view.physicalSize = const Size(1200, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(const ManagementApp());
      await settle(tester);
      await tester.tap(find.text('Servicii'));
      await tester.pumpAndSettle();
    }

    testWidgets('serviciul adăugat se salvează și se trimite partenerului',
        (tester) async {
      await openServices(tester);
      expect(find.text('Spatiere'), findsNothing);
      expect(find.text('Servicii – Tabel 1'), findsOneWidget);

      await tester.tap(find.text('Adaugă serviciu'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).first, 'Gel');
      await tester.tap(find.widgetWithText(ChoiceChip, '1h 30min'));
      await tester.pump();
      await tester.tap(find.widgetWithText(FilledButton, 'Salvează'));
      await tester.pumpAndSettle();
      expect(find.text('Gel'), findsOneWidget);

      await tester.tap(find.byType(BackButton));
      await settle(tester);

      final prefs = await SharedPreferences.getInstance();
      const expected = [SalonService(name: 'Gel', durationMin: 90)];
      expect(loadServices(prefs, 'b1'), expected);
      expect(sentSync(), [servicesSyncMessage(30, expected)]);
    });

    testWidgets('fără modificări nu se trimite nimic', (tester) async {
      await openServices(tester);
      await tester.tap(find.byType(BackButton));
      await settle(tester);
      expect(sentSync(), isEmpty);
    });

    testWidgets('numele duplicat e refuzat', (tester) async {
      final prefs = await SharedPreferences.getInstance();
      await saveServices(
          prefs, 'b1', const [SalonService(name: 'Gel', durationMin: 90)]);
      await openServices(tester);
      await tester.tap(find.text('Adaugă serviciu'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).first, ' gel ');
      await tester.tap(find.widgetWithText(FilledButton, 'Salvează'));
      await tester.pumpAndSettle();
      expect(find.text('Există deja un serviciu cu acest nume.'),
          findsOneWidget);
    });

    testWidgets('lista primită de la partener înlocuiește lista tabelului',
        (tester) async {
      queue.add({
        'id': '1',
        'board': 'b3',
        'msg': servicesSyncMessage(
            40, const [SalonService(name: 'Tuns', durationMin: 40)]),
        'origin': 'partner',
      });
      await tester.pumpWidget(const ManagementApp());
      await settle(tester);

      final prefs = await SharedPreferences.getInstance();
      expect(loadServices(prefs, 'b3'),
          [const SalonService(name: 'Tuns', durationMin: 40)]);
      expect(prefs.getInt('appointment_duration_b3'), 40);
      expect(queue, isEmpty);
      // Mesajul venit de la partener nu se trimite înapoi.
      expect(sentSync(), isEmpty);
    });

    testWidgets('rezervarea botului își păstrează serviciul în tabel',
        (tester) async {
      queue.add({
        'id': '1',
        'board': 'b1',
        'msg': 'ORG:A:${jsonEncode({
          's': 'bot1',
          'n': '+40712345678',
          'c': '2030-01-14T09:00',
          'e': '2030-01-14T10:30',
          'p1': '+40712345678',
          'b': true,
          'v': 'Gel',
          'm': 90,
        })}',
        'origin': 'local',
      });
      tester.view.physicalSize = const Size(1200, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(const ManagementApp());
      await settle(tester);

      final prefs = await SharedPreferences.getInstance();
      final items = jsonDecode(prefs.getString('management_items_b1')!) as List;
      final saved = Item.fromJson(items.single as Map<String, dynamic>);
      expect((saved.service, saved.durationMin), ('Gel', 90));
      // Trimisă și partenerului, cu serviciul.
      expect(sentSync().single, contains('"v":"Gel"'));
    });

    testWidgets('la adăugare se alege serviciul tabelului', (tester) async {
      final prefs = await SharedPreferences.getInstance();
      await saveServices(prefs, 'b1', const [
        SalonService(name: 'Gel', durationMin: 90),
        SalonService(name: 'Ojă', durationMin: 30),
      ]);
      tester.view.physicalSize = const Size(1200, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(const ManagementApp());
      await settle(tester);

      await tester.tap(find.byIcon(Icons.add_circle_rounded));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).first, 'Ana');
      await tester.tap(find.byType(DropdownButtonFormField<SalonService?>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Gel (1h 30min)').last);
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Adaugă'));
      await settle(tester);

      final items = jsonDecode(prefs.getString('management_items_b1')!) as List;
      final saved = Item.fromJson(items.single as Map<String, dynamic>);
      expect((saved.service, saved.durationMin), ('Gel', 90));
      expect(find.text('Gel'), findsOneWidget); // coloana Descriere
    });

    testWidgets('„Arată orele libere” calculează cu durata serviciului ales',
        (tester) async {
      final prefs = await SharedPreferences.getInstance();
      await saveServices(prefs, 'b1', const [
        SalonService(name: 'Gel', durationMin: 90),
        SalonService(name: 'Ojă', durationMin: 30),
      ]);
      await openServices(tester);
      await tester.tap(find.text('Arată orele libere în tabel'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Ojă (30 min)'));
      await settle(tester);

      final call = calls.lastWhere((c) => c.method == 'computeFreeSlots');
      expect((call.arguments as Map)['durationMin'], 30);

      // Din nou în pagină: butonul ascunde rândurile libere.
      await tester.tap(find.text('Servicii'));
      await tester.pumpAndSettle();
      expect(find.text('Ascunde orele libere din tabel'), findsOneWidget);
    });
  });
}
