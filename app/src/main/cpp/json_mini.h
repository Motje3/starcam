// Minimal flat-JSON parse/build for StarCam's JNI payloads. Header-only, no dependencies.
// Supported input: ONE flat object whose values are numbers, strings, booleans, null, or arrays of
// numbers. Nested objects/arrays-of-strings are NOT supported (our payloads never use them).
#pragma once

#include <cmath>
#include <cstdlib>
#include <map>
#include <string>
#include <vector>

namespace jsonmini {

struct Value {
    enum Type { NUM, STR, BOOL, ARR, NUL } type = NUL;
    double num = 0;
    std::string str;
    bool b = false;
    std::vector<double> arr;
};

class Obj {
public:
    // Returns false on malformed input or unsupported nesting.
    static bool parse(const std::string& s, Obj* out) {
        out->m_.clear();
        size_t i = 0;
        skipWs(s, i);
        if (i >= s.size() || s[i] != '{') return false;
        ++i;
        skipWs(s, i);
        if (i < s.size() && s[i] == '}') return true;  // empty object
        while (i < s.size()) {
            std::string key;
            if (!parseString(s, i, &key)) return false;
            skipWs(s, i);
            if (i >= s.size() || s[i] != ':') return false;
            ++i;
            skipWs(s, i);
            Value v;
            if (!parseValue(s, i, &v)) return false;
            out->m_[key] = v;
            skipWs(s, i);
            if (i >= s.size()) return false;
            if (s[i] == ',') { ++i; skipWs(s, i); continue; }
            if (s[i] == '}') return true;
            return false;
        }
        return false;
    }

    bool has(const std::string& k) const { return m_.count(k) != 0; }
    double num(const std::string& k, double def) const {
        auto it = m_.find(k);
        if (it == m_.end()) return def;
        if (it->second.type == Value::NUM) return it->second.num;
        if (it->second.type == Value::BOOL) return it->second.b ? 1.0 : 0.0;
        return def;
    }
    int numi(const std::string& k, int def) const { return (int)std::lround(num(k, def)); }
    bool boolean(const std::string& k, bool def) const {
        auto it = m_.find(k);
        if (it == m_.end()) return def;
        if (it->second.type == Value::BOOL) return it->second.b;
        if (it->second.type == Value::NUM) return it->second.num != 0;
        return def;
    }
    std::string str(const std::string& k, const std::string& def) const {
        auto it = m_.find(k);
        return (it != m_.end() && it->second.type == Value::STR) ? it->second.str : def;
    }
    std::vector<double> arr(const std::string& k) const {
        auto it = m_.find(k);
        return (it != m_.end() && it->second.type == Value::ARR) ? it->second.arr
                                                                 : std::vector<double>();
    }

private:
    std::map<std::string, Value> m_;

    static void skipWs(const std::string& s, size_t& i) {
        while (i < s.size() && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) ++i;
    }
    static bool parseString(const std::string& s, size_t& i, std::string* out) {
        skipWs(s, i);
        if (i >= s.size() || s[i] != '"') return false;
        ++i;
        out->clear();
        while (i < s.size()) {
            char c = s[i];
            if (c == '"') { ++i; return true; }
            if (c == '\\') {
                ++i;
                if (i >= s.size()) return false;
                char e = s[i];
                switch (e) {
                    case '"': out->push_back('"'); break;
                    case '\\': out->push_back('\\'); break;
                    case '/': out->push_back('/'); break;
                    case 'n': out->push_back('\n'); break;
                    case 't': out->push_back('\t'); break;
                    case 'r': out->push_back('\r'); break;
                    case 'b': out->push_back('\b'); break;
                    case 'f': out->push_back('\f'); break;
                    case 'u': {
                        // Keep it simple: skip 4 hex digits, emit '?' for non-ASCII escapes.
                        if (i + 4 >= s.size()) return false;
                        i += 4;
                        out->push_back('?');
                        break;
                    }
                    default: return false;
                }
                ++i;
            } else {
                out->push_back(c);
                ++i;
            }
        }
        return false;
    }
    static bool parseValue(const std::string& s, size_t& i, Value* v) {
        skipWs(s, i);
        if (i >= s.size()) return false;
        char c = s[i];
        if (c == '"') {
            v->type = Value::STR;
            return parseString(s, i, &v->str);
        }
        if (c == 't') {
            if (s.compare(i, 4, "true") != 0) return false;
            i += 4;
            v->type = Value::BOOL;
            v->b = true;
            return true;
        }
        if (c == 'f') {
            if (s.compare(i, 5, "false") != 0) return false;
            i += 5;
            v->type = Value::BOOL;
            v->b = false;
            return true;
        }
        if (c == 'n') {
            if (s.compare(i, 4, "null") != 0) return false;
            i += 4;
            v->type = Value::NUL;
            return true;
        }
        if (c == '[') {
            ++i;
            v->type = Value::ARR;
            v->arr.clear();
            skipWs(s, i);
            if (i < s.size() && s[i] == ']') { ++i; return true; }
            while (i < s.size()) {
                const char* start = s.c_str() + i;
                char* endp = nullptr;
                double d = strtod(start, &endp);
                if (endp == start) return false;
                v->arr.push_back(d);
                i += (size_t)(endp - start);
                skipWs(s, i);
                if (i >= s.size()) return false;
                if (s[i] == ',') { ++i; skipWs(s, i); continue; }
                if (s[i] == ']') { ++i; return true; }
                return false;
            }
            return false;
        }
        // number
        {
            const char* start = s.c_str() + i;
            char* endp = nullptr;
            double d = strtod(start, &endp);
            if (endp == start) return false;
            i += (size_t)(endp - start);
            v->type = Value::NUM;
            v->num = d;
            return true;
        }
    }
};

// Streaming JSON object builder with escaping. Numbers are emitted with %.10g.
class Builder {
public:
    Builder() { s_ = "{"; }
    Builder& add(const std::string& k, double v) {
        char buf[48];
        snprintf(buf, sizeof(buf), "%.10g", v);
        addRaw(k, std::isfinite(v) ? buf : "0");
        return *this;
    }
    Builder& add(const std::string& k, int v) { return add(k, (double)v); }
    Builder& add(const std::string& k, int64_t v) {
        char buf[32];
        snprintf(buf, sizeof(buf), "%lld", (long long)v);
        addRaw(k, buf);
        return *this;
    }
    Builder& add(const std::string& k, bool v) { addRaw(k, v ? "true" : "false"); return *this; }
    Builder& add(const std::string& k, const std::string& v) {
        addRaw(k, "\"" + escape(v) + "\"");
        return *this;
    }
    Builder& add(const std::string& k, const char* v) { return add(k, std::string(v)); }
    Builder& add(const std::string& k, const std::vector<double>& v) {
        std::string a = "[";
        for (size_t i = 0; i < v.size(); ++i) {
            char buf[48];
            snprintf(buf, sizeof(buf), "%.10g", std::isfinite(v[i]) ? v[i] : 0.0);
            if (i) a += ",";
            a += buf;
        }
        a += "]";
        addRaw(k, a);
        return *this;
    }
    // Embed a pre-built JSON value (object/array) verbatim.
    Builder& addJson(const std::string& k, const std::string& rawJson) {
        addRaw(k, rawJson);
        return *this;
    }
    std::string build() { return s_ + "}"; }

    static std::string escape(const std::string& v) {
        std::string o;
        o.reserve(v.size() + 8);
        for (char c : v) {
            switch (c) {
                case '"': o += "\\\""; break;
                case '\\': o += "\\\\"; break;
                case '\n': o += "\\n"; break;
                case '\t': o += "\\t"; break;
                case '\r': o += "\\r"; break;
                default:
                    if ((unsigned char)c < 0x20) {
                        char buf[8];
                        snprintf(buf, sizeof(buf), "\\u%04x", c);
                        o += buf;
                    } else {
                        o.push_back(c);
                    }
            }
        }
        return o;
    }

private:
    std::string s_;
    bool first_ = true;
    void addRaw(const std::string& k, const std::string& raw) {
        if (!first_) s_ += ",";
        first_ = false;
        s_ += "\"" + escape(k) + "\":" + raw;
    }
};

}  // namespace jsonmini
