package service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dao.AIChatLogDAO;
import dao.RoomDAO;
import model.Room;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatBotService {

    private final RoomDAO roomDAO = new RoomDAO();
    private final AIChatLogDAO chatLogDAO = new AIChatLogDAO();

    public JsonObject processChatRequest(String userMessage, boolean showMore) throws Exception {
        System.out.println("🤖 Processing message: " + userMessage + " | showMore: " + showMore);
        
        // ✅ FIXED: Handle negative response
        if (userMessage.toLowerCase().matches(".*không.*cảm ơn.*|.*không.*thanks.*|.*no.*thank.*")) {
            return createSuccessResponse(
                "Cảm ơn bạn đã sử dụng dịch vụ! 😊\n\n" +
                "💡 **Mẹo nhỏ**: Bạn có thể hỏi tôi:\n" +
                "• \"Phòng giá rẻ cho 2 người\"\n" +
                "• \"Phòng ở Đà Nẵng dưới 1 triệu\"\n" +
                "• \"Phòng từ ngày 25/12 đến 27/12\"\n\n" +
                "Tôi luôn sẵn sàng hỗ trợ bạn! 🏨✨"
            );
        }

        // Parse user requirements with improved extraction
        ParsedInfo info = extractInfoFromMessage(userMessage);
        
        // ✅ IMPROVED: Better date validation
        LocalDate today = LocalDate.now();
        if (info.checkinDate == null) {
            info.checkinDate = Date.valueOf(today.plusDays(1));
        }
        if (info.checkoutDate == null) {
            info.checkoutDate = Date.valueOf(today.plusDays(2));
        }

        // Validate dates
        if (info.checkoutDate.before(info.checkinDate) || info.checkoutDate.equals(info.checkinDate)) {
            return createErrorResponse(
                "❌ **Ngày không hợp lệ**\n\n" +
                "🗓️ Ngày trả phòng phải **sau** ngày nhận phòng ít nhất 1 ngày.\n\n" +
                "**Ví dụ đúng**: \"Phòng từ 25/12 đến 27/12\"\n" +
                "**Thử lại**: Nhập lại với ngày hợp lệ nhé! 😊"
            );
        }

        // ✅ FIXED: Check date not in the past
        if (info.checkinDate.before(Date.valueOf(today))) {
            return createErrorResponse(
                "⏰ **Ngày đã qua**\n\n" +
                "📅 Không thể đặt phòng cho ngày trong quá khứ.\n\n" +
                "**Hôm nay**: " + today.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + "\n" +
                "**Gợi ý**: Thử \"phòng từ ngày mai\" hoặc chọn ngày cụ thể! 😊"
            );
        }

        // Get filtered rooms with proper availability check
        String roomsHtml = getFilteredRoomsWithAvailability(
            userMessage, showMore, info
        );
        
        if ("KHONG_CO_PHONG".equals(roomsHtml)) {
            String noRoomMessage = createNoRoomFoundMessage(info);
            return createSuccessResponse(noRoomMessage);
        }

        // Create AI-like response
        String aiResponse = createSmartResponse(userMessage, roomsHtml, info);

        // ✅ IMPROVED: Better error handling for chat log
        try {
            chatLogDAO.saveChatLog(getCurrentUserId(), userMessage, aiResponse);
        } catch (Exception e) {
            System.err.println("⚠️ Warning: Could not save chat log - " + e.getMessage());
            // Continue without failing the main request
        }

        return createSuccessResponse(aiResponse);
    }

    /**
     * ✅ IMPROVED: Get rooms with comprehensive filtering
     */
    private String getFilteredRoomsWithAvailability(String userMessage, boolean showMore, 
                                               ParsedInfo info) {
    try {
        String city = extractCityFromMessage(userMessage);
        System.out.println("🔍 DEBUG - Extracted city: '" + city + "'");
        System.out.println("🔍 DEBUG - Guests: " + info.guests + ", Checkin: " + info.checkinDate + ", Checkout: " + info.checkoutDate);
        
        // ✅ IMPROVED: Thêm debug log và error handling
        List<Room> rooms;
        
        // Try the main method first
        try {
            rooms = roomDAO.getAvailableRoomss(city, info.guests, info.checkinDate, info.checkoutDate, info.priceCategory);
            System.out.println("🔍 DEBUG - Found " + rooms.size() + " rooms with main method");
        } catch (Exception e) {
            System.err.println("❌ Main method failed, trying simple method: " + e.getMessage());
            // Fallback to simple method
            rooms = roomDAO.getAvailableRoomsSimple(city, info.guests, info.checkinDate, info.checkoutDate);
            System.out.println("🔍 DEBUG - Found " + rooms.size() + " rooms with simple method");
        }

        // ✅ DEBUG: Log a sample room if available
        if (!rooms.isEmpty()) {
            Room sample = rooms.get(0);
            System.out.println("🔍 DEBUG - Sample room: " + sample.getTitle() + 
                " | City: " + (sample.getPropertyId() != null ? sample.getPropertyId().getCity() : "NULL"));
        }

        // Additional filtering (keep existing logic
        if (info.priceCategory != null && !info.priceCategory.isEmpty()) {
            // Thêm lọc khoảng giá chính xác nếu có
if (info.minPrice != null && info.maxPrice != null) {
    int before = rooms.size();
    rooms.removeIf(room -> room.getPrice().compareTo(info.minPrice) < 0 || room.getPrice().compareTo(info.maxPrice) > 0);
    System.out.println("🔍 DEBUG - After min-max price filter: " + rooms.size() + " (was " + before + ")");
}

            int beforeFilter = rooms.size();
           rooms.removeIf(room -> !matchPriceFilter(room.getPrice(), info.priceCategory));
            System.out.println("🔍 DEBUG - After price filter: " + rooms.size() + " (was " + beforeFilter + ")");
        }

        // Type filtering
        String roomType = extractRoomTypeFromMessage(userMessage);
        if (roomType != null && !roomType.isEmpty()) {
            int beforeFilter = rooms.size();
            rooms.removeIf(room -> !matchRoomType(room.getType(), roomType));
            System.out.println("🔍 DEBUG - After type filter: " + rooms.size() + " (was " + beforeFilter + ")");
        }

        // Limit results if not showing more
        if (!showMore && rooms.size() > 3) {
            rooms = rooms.subList(0, 3);
            System.out.println("🔍 DEBUG - Limited to 3 rooms for display");
        }

        if (rooms.isEmpty()) {
            System.out.println("❌ DEBUG - No rooms found after all filters");
            return "KHONG_CO_PHONG";
        }

        return formatRoomsAsHtml(rooms, info.checkinDate, info.checkoutDate);
        
    } catch (Exception e) {
        System.err.println("❌ Error in getFilteredRoomsWithAvailability: " + e.getMessage());
        e.printStackTrace();
        
        // ✅ FALLBACK: Try to get any available rooms
        try {
            System.out.println("🔄 Trying fallback query...");
            List<Room> fallbackRooms = roomDAO.getLatestRooms(5);
            if (!fallbackRooms.isEmpty()) {
                return formatRoomsAsHtml(fallbackRooms.subList(0, Math.min(3, fallbackRooms.size())), 
                                       info.checkinDate, info.checkoutDate);
            }
        } catch (Exception fallbackError) {
            System.err.println("❌ Even fallback failed: " + fallbackError.getMessage());
        }
        
        return "KHONG_CO_PHONG";
    }
}

    /**
     * ✅ IMPROVED: Better room formatting with pricing
     */
    private String formatRoomsAsHtml(List<Room> rooms, Date checkinDate, Date checkoutDate) {
        StringBuilder html = new StringBuilder();
        
        // Calculate number of nights
        long diffInMs = checkoutDate.getTime() - checkinDate.getTime();
        int nights = (int) (diffInMs / (1000 * 60 * 60 * 24));
        
        for (int i = 0; i < rooms.size(); i++) {
            Room room = rooms.get(i);
            String image = extractFirstImage(room.getImages());
            BigDecimal totalPrice = room.getPrice().multiply(BigDecimal.valueOf(nights));
            
         html.append(String.format("""
    <div class='room-suggestion' style='cursor: pointer;' onclick="window.location.href='detail?id=%d'">
        <div style='display: flex; gap: 12px;'>
            <img src='%s' alt='%s' style='width: 85px; height: 65px; object-fit: cover; border-radius: 8px; flex-shrink: 0; border: 1px solid #ddd;'>
            <div style='flex: 1; min-width: 0;'>
                <div style='display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 6px;'>
                    <strong style='color: #007bff; font-size: 15px; line-height: 1.3;'>🏨 %s</strong>
                    <span style='color: #dc3545; font-weight: bold; font-size: 13px; white-space: nowrap;'>⭐ %s</span>
                </div>
                <div style='margin-bottom: 8px;'>
                    <span style='color: #28a745; font-weight: bold; font-size: 16px;'>%,.0f₫</span>
                    <span style='color: #6c757d; font-size: 11px;'>/đêm • %d khách</span>
                </div>
                <div style='color: #666; font-size: 12px; line-height: 1.4; margin-bottom: 6px;'>%s</div>
                <div style='background: linear-gradient(45deg, #e8f5e8, #f0f8ff); padding: 6px 8px; border-radius: 6px; font-size: 12px;'>
                    <strong style='color: #155724;'>💰 Tổng %d đêm: %,.0f₫</strong>
                </div>
            </div>
        </div>
    </div> 
""",
    room.getRoomId(),
    image,
    room.getTitle(),
    room.getTitle(),
    room.getType() != null ? room.getType() : "Standard",
    room.getPrice().doubleValue(), // ✅ sửa chỗ này
    room.getCapacity(),
    truncateDescription(room.getDescription(), 50),
    nights,
    totalPrice.doubleValue() // ✅ sửa chỗ này
));

        }
        
        return html.toString();
    }

    /**
     * ✅ IMPROVED: Smarter response generation
     */
    private String createSmartResponse(String userMessage, String roomsHtml, ParsedInfo info) {
        String greeting = getContextualGreeting(userMessage, info);
        
        long nights = (info.checkoutDate.getTime() - info.checkinDate.getTime()) / (1000 * 60 * 60 * 24);
        
        String summary = String.format(
            "🎯 **Tìm thấy phòng phù hợp**\n" +
            "👥 %d khách • 📅 %d đêm (%s ➜ %s)\n\n",
            info.guests,
            nights,
            formatVietnameseDate(info.checkinDate),
            formatVietnameseDate(info.checkoutDate)
        );
        
     
            
        String question = "🤔 Bạn có muốn **xem thêm** phòng khác không?";
        
        return greeting + summary + roomsHtml  + question;
    }

    /**
     * ✅ NEW: Create no room found message
     */
private String createNoRoomFoundMessage(ParsedInfo info) {
    String priceText;
    if (info.minPrice != null && info.maxPrice != null) {
        priceText = String.format("Từ %,.0f₫ đến %,.0f₫", info.minPrice.doubleValue(), info.maxPrice.doubleValue());
    } else if (info.priceCategory != null) {
        priceText = getPriceCategoryDisplay(info.priceCategory);
    } else {
        priceText = "Mọi mức giá";
    }
    return String.format(
        "😔 **Không tìm thấy phòng phù hợp**\n\n" +
        "🔍 **Yêu cầu của bạn**:\n" +
        "👥 %d khách\n" +
        "📅 %s ➜ %s\n" +
        "💰 %s\n\n" +
        "💡 **Gợi ý khác**:\n" +
        "• 📅 Thử ngày linh hoạt hơn\n" +
        "• 👥 Giảm số khách hoặc tách phòng\n" +
        "• 💰 Mở rộng ngân sách\n" +
        "• 🗺️ Xem khu vực lân cận\n\n",
        info.guests,
        formatVietnameseDate(info.checkinDate),
        formatVietnameseDate(info.checkoutDate),
        priceText
    );
}

    // ====================== HELPER METHODS ============================

    private String getContextualGreeting(String message, ParsedInfo info) {
        String msg = message.toLowerCase();
        if (msg.contains("cấp tốc") || msg.contains("gấp") || msg.contains("urgent")) {
            return "⚡ **Booking cấp tốc!** ";
        } else if (msg.contains("gia đình") || msg.contains("family")) {
            return "👨‍👩‍👧‍👦 **Kỳ nghỉ gia đình** ";
        } else if (msg.contains("couple") || msg.contains("cặp đôi") || msg.contains("honeymoon")) {
            return "💕 **Nghỉ dưỡng lãng mạn** ";
        } else if (msg.contains("business") || msg.contains("công tác") || msg.contains("doanh nhân")) {
            return "💼 **Chuyến công tác** ";
        } else if (info.guests >= 5) {
            return "👥 **Nhóm đông người** ";
        }
        return "🏨 ";
    }

    private String formatVietnameseDate(Date date) {
        LocalDate localDate = date.toLocalDate();
        return localDate.format(DateTimeFormatter.ofPattern("dd/MM"));
    }

    private String truncateDescription(String desc, int maxLength) {
        if (desc == null) return "Phòng đẹp, tiện nghi đầy đủ";
        if (desc.length() <= maxLength) return desc;
        return desc.substring(0, maxLength) + "...";
    }

    private String getPriceCategoryDisplay(String category) {
        return switch (category.toLowerCase()) {
            case "gia re" -> "Dưới 500K";
            case "trung binh thap" -> "500K - 1TR";
            case "trung binh cao" -> "1TR - 2TR";
            case "mr beast" -> "Trên 2TR";
            default -> "Mọi mức giá";
        };
    }

    /**
     * ✅ NEW: Extract room type from message
     */
    private String extractRoomTypeFromMessage(String message) {
        if (message == null) return null;
        message = message.toLowerCase();
        
        if (message.contains("deluxe")) return "Deluxe";
        if (message.contains("suite") || message.contains("suit")) return "Suite";
        if (message.contains("standard") || message.contains("tiêu chuẩn")) return "Standard";
        if (message.contains("family") || message.contains("gia đình")) return "Family";
        if (message.contains("business") || message.contains("công tác")) return "Business";
        if (message.contains("honeymoon") || message.contains("tình yêu")) return "Honeymoon";
        if (message.contains("studio")) return "Studio";
        if (message.contains("view biển") || message.contains("sea view")) return "SeaView";
        
        return null;
    }

    /**
     * ✅ NEW: Match room type
     */
    private boolean matchRoomType(String roomType, String requestedType) {
        if (roomType == null || requestedType == null) return true;
        return roomType.toLowerCase().contains(requestedType.toLowerCase()) ||
               requestedType.toLowerCase().contains(roomType.toLowerCase());
    }

    private int getCurrentUserId() {
        // TODO: Get from session or JWT token
        return 1; // Default user for now
    }

    private JsonObject createSuccessResponse(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", message);
        return response;
    }

    private JsonObject createErrorResponse(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("success", true); // Still success to show message
        response.addProperty("message", message);
        return response;
    }

    private String extractFirstImage(String images) {
        if (images == null || images.trim().isEmpty()) {
            return "https://via.placeholder.com/300x200?text=No+Image";
        }
        
        try {
            JsonArray imageArray = JsonParser.parseString(images).getAsJsonArray();
            if (imageArray.size() > 0) {
                String imagePath = imageArray.get(0).getAsString().replace("\"", "").trim();
                if (!imagePath.startsWith("http")) {
                    imagePath = "img/" + imagePath;
                }
                return imagePath;
            }
        } catch (Exception e) {
            // Fallback: treat as simple string
            String[] parts = images.split(",");
            if (parts.length > 0) {
                String imagePath = parts[0].trim().replace("\"", "").replace("[", "").replace("]", "");
                if (!imagePath.startsWith("http")) {
                    imagePath = "img/" + imagePath;
                }
                return imagePath;
            }
        }
        
        return "https://via.placeholder.com/300x200?text=No+Image";
    }

   private String extractCityFromMessage(String message) {
    if (message == null) {
        System.out.println("🔍 DEBUG - Message is null, returning empty city");
        return "";
    }
    
    String originalMessage = message;
    message = message.toLowerCase().trim();
    System.out.println("🔍 DEBUG - Processing message for city: '" + originalMessage + "'");
    
    // Vietnamese city detection with more variations
    String[] cityPatterns = {
        "nha\\s*trang", "Nha Trang",
        "đà\\s*nẵng|da\\s*nang", "Đà Nẵng", 
        "đà\\s*lạt|da\\s*lat|dalat", "Đà Lạt",
        "hà\\s*nội|ha\\s*noi|hanoi", "Hà Nội",
        "hồ\\s*chí\\s*minh|ho\\s*chi\\s*minh|saigon|sài\\s*gòn|tphcm", "Hồ Chí Minh",
        "cần\\s*thơ|can\\s*tho", "Cần Thơ",
        "vũng\\s*tàu|vung\\s*tau", "Vũng Tàu"
    };
    
    for (int i = 0; i < cityPatterns.length; i += 2) {
        if (message.matches(".*" + cityPatterns[i] + ".*")) {
            String detectedCity = cityPatterns[i + 1];
            System.out.println("🔍 DEBUG - Detected city: '" + detectedCity + "'");
            return detectedCity;
        }
    }
    
    System.out.println("🔍 DEBUG - No city detected, searching all cities");
    return ""; // Empty string means search all cities
}

    // ✅ IMPROVED: Better info extraction with more patterns
    private ParsedInfo extractInfoFromMessage(String message) {
    ParsedInfo info = new ParsedInfo();
    if (message == null) return info;

    String originalMessage = message;
    message = message.toLowerCase();

    // ===== 1. Extract số khách =====
    Pattern[] guestPatterns = {
        Pattern.compile("(?:cho|cần|phòng)?\\s*(\\d{1,2})\\s*(?:người|khách|ng|guest)"),
        Pattern.compile("(\\d{1,2})\\s*(?:pax|adult)"),
        Pattern.compile("(?:couple|cặp\\s*đôi)"),
        Pattern.compile("(?:family|gia\\s*đình)"),
        Pattern.compile("(?:nhóm|group)\\s*(\\d{1,2})")
    };

    for (Pattern pattern : guestPatterns) {
        Matcher matcher = pattern.matcher(message);
        if (matcher.find()) {
            if (pattern.pattern().contains("couple") || pattern.pattern().contains("cặp")) {
                info.guests = 2;
                break;
            } else if (pattern.pattern().contains("family") || pattern.pattern().contains("gia")) {
                info.guests = 4;
                break;
            } else {
                try {
                    int guests = Integer.parseInt(matcher.group(1));
                    if (guests > 0 && guests <= 20) {
                        info.guests = guests;
                        break;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    // ===== 2. Extract khoảng giá =====
    Pattern priceRangePattern = Pattern.compile("từ\\s*(\\d+(?:\\.\\d+)?)(k|tr)?\\s*(?:đến|tới|->|-)\\s*(\\d+(?:\\.\\d+)?)(k|tr)?");
    Matcher rangeMatcher = priceRangePattern.matcher(message);
    
if (rangeMatcher.find()) {
    try {
        double from = Double.parseDouble(rangeMatcher.group(1));
        String fromUnit = rangeMatcher.group(2) != null ? rangeMatcher.group(2) : "k";
        double to = Double.parseDouble(rangeMatcher.group(3));
        String toUnit = rangeMatcher.group(4) != null ? rangeMatcher.group(4) : "k";

        BigDecimal fromPrice = BigDecimal.valueOf(fromUnit.contains("tr") ? from * 1_000_000 : from * 1_000);
        BigDecimal toPrice = BigDecimal.valueOf(toUnit.contains("tr") ? to * 1_000_000 : to * 1_000);

        info.minPrice = fromPrice;
        info.maxPrice = toPrice;

        // Optional: gán category dựa trên toPrice
        if (toPrice.compareTo(new BigDecimal("500000")) < 0) {
            info.priceCategory = "gia re";
        } else if (toPrice.compareTo(new BigDecimal("1000000")) <= 0) {
            info.priceCategory = "trung binh thap";
        } else if (toPrice.compareTo(new BigDecimal("2000000")) <= 0) {
            info.priceCategory = "trung binh cao";
        } else {
            info.priceCategory = "mr beast";
        }

        System.out.println("🔍 Detected price range: " + fromPrice + " -> " + toPrice);
    } catch (Exception e) {
        System.err.println("⚠️ Parse price range failed: " + e.getMessage());
    }
}

     else {
        // ===== 3. Extract giá đơn lẻ như "800k", "1.2tr" =====
        Pattern singlePricePattern = Pattern.compile("(\\d+(?:\\.\\d+)?)(k|tr)");
        Matcher priceMatcher = singlePricePattern.matcher(message);
        if (priceMatcher.find()) {
            try {
                double val = Double.parseDouble(priceMatcher.group(1));
                String unit = priceMatcher.group(2);
               BigDecimal price = BigDecimal.valueOf(unit.contains("tr") ? val * 1_000_000 : val * 1_000);
                info.maxPrice = price;

                if (price.compareTo(new BigDecimal("500000")) < 0) {
    info.priceCategory = "gia re";
} else if (price.compareTo(new BigDecimal("1000000")) <= 0) {
    info.priceCategory = "trung binh thap";
} else if (price.compareTo(new BigDecimal("2000000")) <= 0) {
    info.priceCategory = "trung binh cao";
} else {
    info.priceCategory = "mr beast";
}


                System.out.println("🔍 Detected price: " + price);
            } catch (Exception e) {
                System.err.println("⚠️ Parse price failed: " + e.getMessage());
            }
        }
    }

    // ===== 4. Backup keyword mapping cho category =====
    if (info.priceCategory == null) {
        if (message.matches(".*trên\s*500k.*|.*trên\s*500\s*nghìn.*|.*over\s*500k.*|.*above\s*500k.*")) {
            info.priceCategory = "trung binh thap";
            info.minPrice = new BigDecimal("500001"); // hoặc 500000.01 nếu muốn
        } else if (message.matches(".*(?:giá\s*rẻ|gia\s*re|tiết\s*kiệm|budget|cheap|dưới\s*500).*")) {
            info.priceCategory = "gia re";
        } else if (message.matches(".*(?:trung\s*bình.*thấp|500k|1tr|mid.*low|medium.*low).*")) {
            info.priceCategory = "trung binh thap";
        } else if (message.matches(".*(?:trung\s*bình.*cao|1tr.*2tr|mid.*high|medium.*high).*")) {
            info.priceCategory = "trung binh cao";
        } else if (message.matches(".*(?:cao\s*cấp|sang\s*trọng|luxury|premium|trên\s*2tr|expensive).*")) {
            info.priceCategory = "mr beast";
        }
    }

    // ===== 5. Extract ngày nhận/trả =====
    info.checkinDate = extractDateFromMessage(originalMessage, "từ|from|check.*in");
    info.checkoutDate = extractDateFromMessage(originalMessage, "đến|to|until|check.*out");

    return info;
}
    private Date extractDateFromMessage(String message, String keywords) {
        try {
            // Multiple date patterns
            Pattern[] datePatterns = {
                Pattern.compile("(?:" + keywords + ")\\s*(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?"),
                Pattern.compile("(?:" + keywords + ")\\s*ngày\\s*(\\d{1,2})\\s*tháng\\s*(\\d{1,2})(?:\\s*năm\\s*(\\d{2,4}))?"),
                Pattern.compile("(?:" + keywords + ")\\s*(\\d{1,2})-(\\d{1,2})(?:-(\\d{2,4}))?")
            };
            
            for (Pattern pattern : datePatterns) {
                Matcher matcher = pattern.matcher(message);
                if (matcher.find()) {
                    int day = Integer.parseInt(matcher.group(1));
                    int month = Integer.parseInt(matcher.group(2));
                    int year = matcher.group(3) != null ? 
                        Integer.parseInt(matcher.group(3)) : 
                        LocalDate.now().getYear();
                    
                    // Handle 2-digit year
                    if (year < 100) {
                        year += 2000;
                    }
                    
                    // Validate date
                    if (day >= 1 && day <= 31 && month >= 1 && month <= 12) {
                        try {
                            LocalDate date = LocalDate.of(year, month, day);
                            return Date.valueOf(date);
                        } catch (Exception e) {
                            // Invalid date, continue
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("⚠️ Date parsing error: " + e.getMessage());
        }
        return null;
    }

    private boolean matchPriceFilter(BigDecimal price, String category) {
        if (price == null || category == null) return true;

        BigDecimal fiveHundredK = new BigDecimal("500000");
        BigDecimal oneMillion = new BigDecimal("1000000");
        BigDecimal twoMillion = new BigDecimal("2000000");

        return switch (category.toLowerCase()) {
            case "gia re" -> price.compareTo(fiveHundredK) < 0;
            case "trung binh thap" -> price.compareTo(fiveHundredK) > 0 && price.compareTo(oneMillion) <= 0;
            case "trung binh cao" -> price.compareTo(oneMillion) > 0 && price.compareTo(twoMillion) <= 0;
            case "mr beast" -> price.compareTo(twoMillion) > 0;
            default -> true;
        };
    }

    // ====================== INFO CLASS ============================
    private static class ParsedInfo {
    int guests = 1;
    Date checkinDate = null;
    Date checkoutDate = null;
    String priceCategory = null;
    BigDecimal minPrice;
    BigDecimal maxPrice;
}

}