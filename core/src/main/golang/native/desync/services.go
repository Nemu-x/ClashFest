package desync

// Services a desync-only profile can route. This file is the only place that
// maps a service ID to its domains: the UI lists Services() and sends IDs back
// to GenerateConfig, so adding a service here is the whole change.
//
// Domains are matched with DOMAIN-SUFFIX, so a domain also covers its
// subdomains. Only names the service's clients reach over TLS belong here:
// desync reshapes the TLS ClientHello and does nothing for other traffic.

// Service is one routable service.
type Service struct {
	ID      string   `json:"id"`
	Name    string   `json:"name"`
	Domains []string `json:"domains"`
	// TestURL is what automatic strategy selection fetches through each
	// candidate: an HTTPS URL of the service, so a candidate passes only
	// when the TLS handshake gets through the filter.
	TestURL string `json:"testUrl"`
	// TransferURL is a page of the service large enough to show a connection
	// that freezes after the first tens of kilobytes (transfer.go); empty
	// uses TestURL. Not sent to the UI.
	TransferURL string `json:"-"`
}

var services = []Service{
	{ID: "youtube", Name: "YouTube", TestURL: "https://www.youtube.com/generate_204", TransferURL: "https://www.youtube.com/", Domains: []string{
		"youtube.com", "youtu.be", "yt.be", "youtube-nocookie.com", "youtubekids.com",
		"googlevideo.com", "ytimg.com", "ggpht.com",
		"youtubei.googleapis.com", "youtube.googleapis.com", "yt3.googleusercontent.com",
	}},
	{ID: "discord", Name: "Discord", TestURL: "https://discord.com/api/v9/gateway", TransferURL: "https://discord.com/login", Domains: []string{
		"discord.com", "discord.gg", "discord.media", "discord.co", "discord.dev", "discord.new",
		"discord.gift", "discordapp.com", "discordapp.net", "discordcdn.com", "discordstatus.com", "dis.gd",
	}},
	{ID: "instagram", Name: "Instagram", TestURL: "https://www.instagram.com/", Domains: []string{
		"instagram.com", "cdninstagram.com", "ig.me", "instagr.am",
	}},
	{ID: "facebook", Name: "Facebook", TestURL: "https://www.facebook.com/", Domains: []string{
		"facebook.com", "facebook.net", "fb.com", "fb.me", "fbcdn.net", "fbsbx.com", "messenger.com", "m.me",
	}},
	{ID: "twitter", Name: "X (Twitter)", TestURL: "https://x.com/", Domains: []string{
		"x.com", "twitter.com", "twimg.com", "t.co",
	}},
	{ID: "telegram", Name: "Telegram (web)", TestURL: "https://telegram.org/", Domains: []string{
		"telegram.org", "telegram.me", "t.me", "telesco.pe", "telegra.ph",
	}},
	{ID: "linkedin", Name: "LinkedIn", TestURL: "https://linkedin.com/", Domains: []string{
		"linkedin.com", "licdn.com", "lnkd.in",
	}},
}

// Services returns a copy of the service table, in display order.
func Services() []Service {
	out := make([]Service, len(services))
	for i, s := range services {
		s.Domains = append([]string(nil), s.Domains...)
		out[i] = s
	}
	return out
}

func serviceByID(id string) (Service, bool) {
	for _, s := range services {
		if s.ID == id {
			return s, true
		}
	}
	return Service{}, false
}
