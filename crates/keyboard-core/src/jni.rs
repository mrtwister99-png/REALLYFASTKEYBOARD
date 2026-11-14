//! Hranice JNI - jedine misto s `unsafe` v celem crate.
//!
//! Zamerne bez crate `jni` (neni v povolenych zavislostech): potrebujeme jen pet funkci
//! z tabulky JNINativeInterface, volame je primo pres jejich pevne indexy dane specifikaci JNI.
//! Retezce se predavaji jako UTF-8 `byte[]` (ne jako java.lang.String), takze odpada
//! "modified UTF-8" a emoji prochazeji bez poskozeni.
//!
//! Kotlin: `cz.rustboard.RustBridge.nativeCall` a `nativeSwipe`.

#![allow(non_snake_case)]

use std::ffi::c_void;
use std::panic::{catch_unwind, AssertUnwindSafe};

type JniEnv = *mut *const c_void;
type JObject = *mut c_void;

// Indexy v JNINativeInterface (JNI 1.6, stabilni ABI).
const IDX_GET_ARRAY_LENGTH: usize = 171;
const IDX_NEW_BYTE_ARRAY: usize = 176;
const IDX_GET_BYTE_ARRAY_REGION: usize = 200;
const IDX_GET_FLOAT_ARRAY_REGION: usize = 205;
const IDX_SET_BYTE_ARRAY_REGION: usize = 208;

type GetArrayLength = unsafe extern "system" fn(JniEnv, JObject) -> i32;
type NewByteArray = unsafe extern "system" fn(JniEnv, i32) -> JObject;
type GetByteArrayRegion = unsafe extern "system" fn(JniEnv, JObject, i32, i32, *mut i8);
type GetFloatArrayRegion = unsafe extern "system" fn(JniEnv, JObject, i32, i32, *mut f32);
type SetByteArrayRegion = unsafe extern "system" fn(JniEnv, JObject, i32, i32, *const i8);

/// Nacte ukazatel na funkci z tabulky JNI.
///
/// # Safety
/// `env` musi byt platny `JNIEnv*` predany JVM a `T` musi byt typ funkce na indexu `idx`.
unsafe fn jni_fn<T: Copy>(env: JniEnv, idx: usize) -> T {
    let table = *env as *const *const c_void;
    let slot = table.add(idx);
    std::mem::transmute_copy::<*const c_void, T>(&*slot)
}

/// # Safety
/// `env` platny JNIEnv*, `arr` null nebo platny `byte[]`.
unsafe fn read_bytes(env: JniEnv, arr: JObject) -> Vec<u8> {
    if env.is_null() || arr.is_null() {
        return Vec::new();
    }
    let len = jni_fn::<GetArrayLength>(env, IDX_GET_ARRAY_LENGTH)(env, arr);
    if len <= 0 {
        return Vec::new();
    }
    let mut buf = vec![0u8; len as usize];
    jni_fn::<GetByteArrayRegion>(env, IDX_GET_BYTE_ARRAY_REGION)(env, arr, 0, len, buf.as_mut_ptr() as *mut i8);
    buf
}

/// # Safety
/// `env` platny JNIEnv*, `arr` null nebo platny `float[]`.
unsafe fn read_floats(env: JniEnv, arr: JObject, count: i32) -> Vec<f32> {
    if env.is_null() || arr.is_null() {
        return Vec::new();
    }
    let len = jni_fn::<GetArrayLength>(env, IDX_GET_ARRAY_LENGTH)(env, arr).min(count);
    if len <= 0 {
        return Vec::new();
    }
    let mut buf = vec![0f32; len as usize];
    jni_fn::<GetFloatArrayRegion>(env, IDX_GET_FLOAT_ARRAY_REGION)(env, arr, 0, len, buf.as_mut_ptr());
    buf
}

/// # Safety
/// `env` platny JNIEnv*.
unsafe fn new_bytes(env: JniEnv, data: &[u8]) -> JObject {
    if env.is_null() || data.len() > i32::MAX as usize {
        return std::ptr::null_mut();
    }
    let arr = jni_fn::<NewByteArray>(env, IDX_NEW_BYTE_ARRAY)(env, data.len() as i32);
    if !arr.is_null() && !data.is_empty() {
        jni_fn::<SetByteArrayRegion>(env, IDX_SET_BYTE_ARRAY_REGION)(env, arr, 0, data.len() as i32, data.as_ptr() as *const i8);
    }
    arr
}

/// `external fun nativeCall(op: Int, a: ByteArray?, b: ByteArray?, num: Long): ByteArray?`
///
/// # Safety
/// Vola pouze JVM s platnymi argumenty podle signatury vyse.
#[no_mangle]
pub unsafe extern "system" fn Java_cz_rustboard_RustBridge_nativeCall(
    env: JniEnv,
    _class: JObject,
    op: i32,
    a: JObject,
    b: JObject,
    num: i64,
) -> JObject {
    let a = read_bytes(env, a);
    let b = read_bytes(env, b);
    // Panika nesmi projit pres hranici FFI (a shodit klavesnici) - vratime null.
    match catch_unwind(AssertUnwindSafe(|| crate::dispatch(op, &a, &b, num))) {
        Ok(out) => new_bytes(env, &out),
        Err(_) => std::ptr::null_mut(),
    }
}

/// `external fun nativeSwipe(points: FloatArray, count: Int, flags: Int): ByteArray?`
/// `points` = trojice (x, y, t), `count` = pocet platnych floatu.
///
/// # Safety
/// Vola pouze JVM s platnymi argumenty podle signatury vyse.
#[no_mangle]
pub unsafe extern "system" fn Java_cz_rustboard_RustBridge_nativeSwipe(
    env: JniEnv,
    _class: JObject,
    points: JObject,
    count: i32,
    flags: i32,
) -> JObject {
    let raw = read_floats(env, points, count);
    match catch_unwind(AssertUnwindSafe(|| crate::swipe_global(&raw, flags as i64))) {
        Ok(out) => new_bytes(env, &out),
        Err(_) => std::ptr::null_mut(),
    }
}
