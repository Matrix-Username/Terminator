#include <jni.h>
#include <android/log.h>
#include <string>
#include <atomic>
#include <thread>
#include <chrono>

#define LOG_TAG "NativeTestLib"
#define LOG_I(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

int g_test_variable = 100;

extern "C" int test_simple_function(int a, int b) {
    return a + b;
}

struct TestData {
    int id;
    double value;
};

extern "C" void test_struct_by_pointer(TestData* data) {
    if (data) {
        data->id += 1;
        data->value *= 2.0;
    }
}

extern "C" const char* test_pointer_args(const char* input_str, int* out_val) {
    if (out_val) {
        *out_val = 500;
    }
    return input_str;
}

class TestClass {
private:
    int m_value;

public:
    TestClass(int initial) : m_value(initial) {
        LOG_I("TestClass instance created with m_value = %d", m_value);
    }

    int instance_method(int multiplier) {
        return m_value * multiplier;
    }

    static const char* static_method() {
        return "Original static string";
    }
};

void run_all_tests() {
    LOG_I("--- Running Native Tests ---");

    LOG_I("[Test 1] Calling test_simple_function(5, 7). Expected result: 12");
    int simple_result = test_simple_function(5, 7);
    LOG_I("[Test 1] Actual result: %d. (Hook should change this to 35)", simple_result);

    TestData data = { .id = 10, .value = 42.5 };
    LOG_I("[Test 2] Calling test_struct_by_pointer. Initial values: id=10, value=42.5");
    LOG_I("[Test 2] Expected values after call: id=11, value=85.0");
    test_struct_by_pointer(&data);
    LOG_I("[Test 2] Actual values after call: id=%d, value=%f. (Hook should change these to -20, -3.14)", data.id, data.value);

    int out_val = 0;
    LOG_I("[Test 3] Calling test_pointer_args. Expected out_val: 500");
    test_pointer_args("Hello from C++", &out_val);
    LOG_I("[Test 3] Actual out_val: %d. (Hook should change this to 999)", out_val);

    TestClass instance(42); // m_value = 42
    LOG_I("[Test 4] Calling instance.instance_method(10). Expected result: 420");
    int instance_result = instance.instance_method(10);
    LOG_I("[Test 4] Actual result: %d. (Hook should change this to 1337)", instance_result);

    LOG_I("[Test 5] Calling TestClass::static_method(). Expected result: 'Original static string'");
    const char* static_result = TestClass::static_method();
    LOG_I("[Test 5] Actual result: '%s'. (Hook should change this)", static_result);


    LOG_I("--- Native Tests Finished ---");
}


void test_runner_thread_func() {
    LOG_I("Test runner thread started. Waiting...");

    std::this_thread::sleep_for(std::chrono::seconds(3));

    LOG_I("Starting native tests now.");
    run_all_tests();
}

__attribute__((constructor))
void on_library_load() {
    LOG_I("libnativetest.so loaded. Spawning test runner thread.");
    std::thread test_thread(test_runner_thread_func);
    test_thread.detach();
}