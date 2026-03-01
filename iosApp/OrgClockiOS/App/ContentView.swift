import SwiftUI
import OrgClockShared

struct ContentView: View {
    private let facade = IosHostFacade()

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("OrgClock iOS Host (M3)")
                .font(.title3)
                .fontWeight(.semibold)

            Text(facade.bootstrapMessage())
                .font(.body)

            Text(facade.sampleParseSummary())
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
    }
}
