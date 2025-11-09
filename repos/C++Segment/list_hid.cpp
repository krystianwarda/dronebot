// Simple HID/USB device enumerator using hidapi
// Cross-platform: Windows, Linux, macOS

#include <iostream>
#include <iomanip>
#include <string>
#include <vector>
#include <algorithm>

#include <hidapi/hidapi.h>

static std::string toLower(const std::string &s) {
	std::string out = s;
	std::transform(out.begin(), out.end(), out.begin(), ::tolower);
	return out;
}
static std::wstring toLowerW(const std::wstring &s) {
	std::wstring out = s;
	std::transform(out.begin(), out.end(), out.begin(), ::towlower);
	return out;
}

int main() {
	if (hid_init() !=0) {
		std::cerr << "Failed to initialize hidapi\n";
		return 1;
	}

	struct hid_device_info* devs = hid_enumerate(0x0,0x0);
	struct hid_device_info* cur = devs;
	int idx =0;

	std::cout << "Enumerating HID devices (via hidapi)\n";

	// keywords to identify Radiomaster / radio transmitter devices
	std::vector<std::wstring> keywords = {
		L"radiomaster", L"tx16s", L"opentx", L"er9x", L"jumper", L"tx16", L"transmitter", L"radi" , L"radio",
		L"joystick", L"gamepad", L"controller"
	};

	std::vector<int> matches;
	int joystick_like_count =0;

	while (cur) {
		std::wcout << L"Device " << idx << L":\n";
		std::cout << " Path: " << (cur->path ? cur->path : "(null)") << '\n';
		std::cout << " Vendor ID:0x" << std::hex << std::setw(4) << std::setfill('0') << cur->vendor_id << std::dec << '\n';
		std::cout << " Product ID:0x" << std::hex << std::setw(4) << std::setfill('0') << cur->product_id << std::dec << '\n';

		std::wstring prod = cur->product_string ? cur->product_string : L"";
		std::wstring manuf = cur->manufacturer_string ? cur->manufacturer_string : L"";
		std::wstring serial = cur->serial_number ? cur->serial_number : L"";

		if (!serial.empty()) std::wcout << L" Serial: " << serial << L'\n';
		if (!manuf.empty()) std::wcout << L" Manufacturer: " << manuf << L'\n';
		if (!prod.empty()) std::wcout << L" Product: " << prod << L'\n';

		std::cout << " Interface: " << static_cast<int>(cur->interface_number) << "\n";
		std::cout << " Usage Page:0x" << std::hex << cur->usage_page << std::dec << "\n";
		std::cout << " Usage:0x" << std::hex << cur->usage << std::dec << "\n";

		bool is_match = false;
		std::vector<std::string> reasons;

		// Check HID usage for joystick/gamepad (Generic Desktop Page0x01)
		if (cur->usage_page ==0x01 && (cur->usage ==0x04 || cur->usage ==0x05 || cur->usage ==0x08)) {
			is_match = true;
			joystick_like_count++;
			reasons.push_back("usage indicates joystick/gamepad");
		}

		// Check product/manufacturer/path strings for keywords
		std::wstring lower_prod = toLowerW(prod);
		std::wstring lower_manuf = toLowerW(manuf);
		std::string path_narrow = cur->path ? std::string(cur->path) : std::string();
		std::string lower_path = toLower(path_narrow);

		for (auto &kw : keywords) {
			if (!kw.empty()) {
				if (!lower_prod.empty() && lower_prod.find(kw) != std::wstring::npos) {
					is_match = true;
					reasons.push_back(std::string("product string contains '") + std::string(kw.begin(), kw.end()) + "'");
				}
				if (!lower_manuf.empty() && lower_manuf.find(kw) != std::wstring::npos) {
					is_match = true;
					reasons.push_back(std::string("manufacturer string contains '") + std::string(kw.begin(), kw.end()) + "'");
				}
				// check path (narrow) too
				std::string kw_narrow(kw.begin(), kw.end());
				if (!lower_path.empty() && lower_path.find(kw_narrow) != std::string::npos) {
					is_match = true;
					reasons.push_back(std::string("device path contains '") + kw_narrow + "'");
				}
			}
		}

		if (is_match) {
			matches.push_back(idx);
			std::cout << " MATCHED: ";
			for (size_t i =0; i < reasons.size(); ++i) {
				std::cout << reasons[i];
				if (i +1 < reasons.size()) std::cout << ", ";
			}
			std::cout << "\n";
		}

		std::cout << " ---\n";

		cur = cur->next;
		++idx;
	}

	std::cout << "Summary:\n";
	std::cout << " Total HID devices: " << idx << "\n";
	std::cout << " Joystick-like (by usage): " << joystick_like_count << "\n";
	std::cout << " Devices matched by heuristics: " << matches.size() << "\n";
	if (!matches.empty()) {
		std::cout << " Matched device indices: ";
		for (size_t i =0; i < matches.size(); ++i) {
			std::cout << matches[i];
			if (i +1 < matches.size()) std::cout << ", ";
		}
		std::cout << "\n";
	} else {
		std::cout << " No obvious Radiomaster or transmitter device found using simple heuristics.\n";
		std::cout << " Suggestions:\n";
		std::cout << " - Ensure your radio is in 'Joystick' or 'PC' mode (not storage/bootloader).\n";
		std::cout << " - Use a data USB cable and try other USB ports.\n";
		std::cout << " - Check Windows Device Manager for unknown devices or drivers.\n";
		std::cout << " - If the radio uses a CDC/serial interface or custom driver, it may not appear as HID joystick; consider using serial/CDC or libusb to communicate.\n";
	}

	hid_free_enumeration(devs);
	hid_exit();
	return 0;
}
