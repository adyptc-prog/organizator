import 'dart:io' show Platform;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

// Rezultatul trimiterii licenței la parteneri: câte telefoane au primit-o și,
// dacă a fost refuzată, de ce ('not_owner' — licența a venit de la partener;
// 'other_partner' — e deja folosită pe alt telefon).
typedef LicenseShareOutcome = ({int sent, String? refused});

// ─── Serviciu licențiere ──────────────────────────────────────────────────────
class LicenseService {
  static const _ch              = MethodChannel('organizator/license');
  static const _kTrialStartKey  = 'trial_start_date';
  static const _kExpiryWarnedOn = 'license_expiry_warned_on';
  static const _trialDays       = 30;
  static const expiryWarningDays = 10;

  // Testele rulează pe desktop — permite simularea Android-ului.
  @visibleForTesting
  static bool? debugIsAndroid;
  static bool get _isAndroid => debugIsAndroid ?? Platform.isAndroid;

  // Fluxul vechi .orgtoken — permanent, fără expirare.
  static bool      _legacyLicensed = false;
  static DateTime? _trialStart;

  // Fluxul nou: licență cu businessId + expirare, cumpărată de pe site
  // (identic ca format cu Fidelio). Rulează alături de vechiul flux .orgtoken,
  // fără să-l înlocuiască — orice cod vechi deja emis rămâne valabil.
  static String  newLicenseStatus = 'missing';
  static String  newLicenseMessage = '';
  static String? newLicenseValidUntil;
  static int?    newLicenseDaysUntilExpiry;
  static bool    newLicenseIsLifetime = false;

  static bool get isNewLicenseActive => newLicenseStatus == 'active';
  static bool get isLegacyLicensed => _legacyLicensed;

  // Non-Android: nelimitat
  static bool get isLicensed =>
      !_isAndroid || _legacyLicensed || isNewLicenseActive;

  static bool get isExpiringSoon =>
      isNewLicenseActive &&
      !newLicenseIsLifetime &&
      newLicenseDaysUntilExpiry != null &&
      newLicenseDaysUntilExpiry! <= expiryWarningDays;

  static DateTime? get validUntilLocal => newLicenseValidUntil == null
      ? null
      : DateTime.tryParse(newLicenseValidUntil!)?.toLocal();

  static bool get isTrialActive {
    if (_trialStart == null) return false;
    return DateTime.now().isBefore(_trialStart!.add(const Duration(days: _trialDays)));
  }

  static int get trialDaysLeft {
    if (_trialStart == null) return 0;
    final expiry = _trialStart!.add(const Duration(days: _trialDays));
    final left   = expiry.difference(DateTime.now()).inDays;
    return left < 0 ? 0 : left;
  }

  static bool get canAdd => isLicensed || isTrialActive;

  static Future<void> load() async {
    if (!_isAndroid) return;
    try {
      _legacyLicensed = await _ch.invokeMethod<bool>('isLicensed') ?? false;
    } catch (_) {}
    await checkNewLicense();
    // Înregistrăm data primei instalări (trial start)
    final prefs = await SharedPreferences.getInstance();
    final saved = prefs.getString(_kTrialStartKey);
    if (saved == null) {
      _trialStart = DateTime.now();
      await prefs.setString(_kTrialStartKey, _trialStart!.toIso8601String());
    } else {
      _trialStart = DateTime.tryParse(saved);
    }
  }

  static Future<String?> getPendingToken() async {
    if (!_isAndroid) return null;
    try {
      return await _ch.invokeMethod<String?>('getPendingToken');
    } catch (_) {
      return null;
    }
  }

  static void _apply(Map<Object?, Object?>? r) {
    newLicenseStatus = (r?['status'] as String?) ?? 'missing';
    newLicenseMessage = (r?['message'] as String?) ?? '';
    newLicenseValidUntil = r?['validUntil'] as String?;
    newLicenseDaysUntilExpiry = r?['daysUntilExpiry'] as int?;
    newLicenseIsLifetime = (r?['isLifetime'] as bool?) ?? false;
  }

  static Future<void> checkNewLicense() async {
    if (!_isAndroid) return;
    try {
      _apply(await _ch.invokeMethod<Map<Object?, Object?>>('checkLicense'));
    } catch (_) {}
  }

  static Future<String> getBusinessId() async {
    if (!_isAndroid) return '';
    try {
      return await _ch.invokeMethod<String>('getBusinessId') ?? '';
    } catch (_) {
      return '';
    }
  }

  // Deschide selectorul nativ de fișiere și importă licența aleasă. Un fișier
  // invalid NU înlocuiește licența existentă — mesajul întors explică de ce a
  // fost respins.
  static Future<({bool success, String message})> pickLicenseFile() async {
    if (!_isAndroid) return (success: false, message: '');
    try {
      final r = await _ch.invokeMethod<Map<Object?, Object?>>('pickLicenseFile');
      final success = r?['status'] == 'active';
      final message = (r?['message'] as String?) ?? '';
      await checkNewLicense();
      return (success: success, message: message);
    } on PlatformException catch (e) {
      if (e.code == 'LICENSE_PICK_CANCELLED') return (success: false, message: '');
      return (success: false, message: e.message ?? e.code);
    } catch (e) {
      return (success: false, message: e.toString());
    }
  }

  // Trimite licența partenerului tabelului [boardId] — nativ decide dacă are
  // voie (licența acoperă exact două telefoane). Întoarce "sent",
  // "no_license", "not_owner", "other_partner" sau "no_partner".
  static Future<String> shareWithBoard(String boardId) async {
    if (!_isAndroid) return 'no_license';
    try {
      return await _ch.invokeMethod<String>(
              'shareLicenseWithBoard', {'boardId': boardId}) ??
          'no_license';
    } catch (_) {
      return 'no_license';
    }
  }

  // De unde vine licența și pe ce telefon (mascat) a fost trimisă.
  static Future<({bool fromPartner, String? sharedWith})> getShareInfo() async {
    if (!_isAndroid) return (fromPartner: false, sharedWith: null);
    try {
      final r = await _ch.invokeMethod<Map<Object?, Object?>>('getLicenseShareInfo');
      return (
        fromPartner: (r?['fromPartner'] as bool?) ?? false,
        sharedWith: r?['sharedWith'] as String?,
      );
    } catch (_) {
      return (fromPartner: false, sharedWith: null);
    }
  }

  // true (o singură dată) după ce licența a fost preluată de la partener.
  static Future<bool> consumePartnerNotice() async {
    if (!_isAndroid) return false;
    try {
      return await _ch.invokeMethod<bool>('consumePartnerNotice') ?? false;
    } catch (_) {
      return false;
    }
  }

  // Avertizarea de expirare apare cel mult o dată pe zi.
  static Future<bool> shouldWarnExpiryToday() async {
    if (!isExpiringSoon) return false;
    final prefs = await SharedPreferences.getInstance();
    final now = DateTime.now();
    final today = '${now.year}-${now.month}-${now.day}';
    if (prefs.getString(_kExpiryWarnedOn) == today) return false;
    await prefs.setString(_kExpiryWarnedOn, today);
    return true;
  }

  static Future<({bool success, String message})> activate(String token) async {
    if (!_isAndroid) {
      return (success: false, message: 'Nu este suportat pe această platformă.');
    }
    try {
      final r = await _ch.invokeMethod<Map<Object?, Object?>>('verifyAndActivate',
          {'token': token});
      final success = (r?['success'] as bool?) ?? false;
      final msg     = (r?['msg']     as String?) ?? '';
      if (success) _legacyLicensed = true;
      return (success: success, message: msg);
    } catch (e) {
      return (success: false, message: e.toString());
    }
  }

  @visibleForTesting
  static void debugReset() {
    debugIsAndroid = null;
    _legacyLicensed = false;
    _trialStart = null;
    _apply(null);
  }
}
